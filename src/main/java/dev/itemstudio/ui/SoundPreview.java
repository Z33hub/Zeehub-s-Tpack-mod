package dev.itemstudio.ui;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.libc.LibCStdlib;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Plays a pack's sound file in the menu and works out its waveform for the cards. Sound files (Ogg Vorbis) are
 * decoded with the stb_vorbis that comes with the game, and played on one OpenAL source of the game's own sound
 * device, so the game's master volume applies. Nothing here is Minecraft code.
 */
public final class SoundPreview {
	/** Bars a waveform is worked out at; drawing picks fewer when the box is small. */
	public static final int BARS = 48;
	private static final float[] LOADING = new float[0];
	private static final float[] NONE = new float[0];

	private static final ExecutorService PLAYER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Item Studio sound");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});
	private static final Map<String, float[]> WAVES = new ConcurrentHashMap<>();

	private static int source;
	private static int buffer;
	private static volatile String playingKey;
	private static volatile long playingUntil;
	private static String lastKey;
	private static int lastTurn;

	private SoundPreview() {
	}

	/** Decoded sound: 16-bit samples (channels interleaved), to be freed with LibCStdlib.free. */
	private record Pcm(ShortBuffer samples, int channels, int rate) {
	}

	private static Pcm decode(byte[] ogg) {
		if (ogg == null || ogg.length < 4) return null;
		ByteBuffer data = MemoryUtil.memAlloc(ogg.length);
		try (MemoryStack stack = MemoryStack.stackPush()) {
			data.put(ogg).flip();
			IntBuffer channels = stack.mallocInt(1);
			IntBuffer rate = stack.mallocInt(1);
			ShortBuffer samples = STBVorbis.stb_vorbis_decode_memory(data, channels, rate);
			if (samples == null) return null;
			if (channels.get(0) < 1 || rate.get(0) < 1) {
				LibCStdlib.free(samples);
				return null;
			}
			return new Pcm(samples, channels.get(0), rate.get(0));
		} finally {
			MemoryUtil.memFree(data);
		}
	}

	/** Loudest sample of each of BARS slices of the sound, 0..1, scaled so the loudest bar is 1. Null if unreadable. */
	public static float[] peaks(byte[] ogg) {
		Pcm pcm;
		try {
			pcm = decode(ogg);
		} catch (Throwable t) {
			return null;
		}
		if (pcm == null) return null;
		try {
			ShortBuffer s = pcm.samples();
			int ch = pcm.channels();
			int frames = s.remaining() / ch;
			if (frames == 0) return null;
			float[] bars = new float[BARS];
			float max = 0;
			for (int i = 0; i < BARS; i++) {
				int from = (int) ((long) i * frames / BARS), to = Math.max(from + 1, (int) ((long) (i + 1) * frames / BARS));
				int peak = 0;
				for (int f = from; f < to && f < frames; f++) {
					for (int c = 0; c < ch; c++) peak = Math.max(peak, Math.abs((int) s.get(f * ch + c)));
				}
				bars[i] = peak / 32768f;
				max = Math.max(max, bars[i]);
			}
			// quiet sounds still show their shape; silence stays flat
			float scale = 1f / Math.max(max, 0.05f);
			for (int i = 0; i < BARS; i++) bars[i] = Math.min(1f, bars[i] * scale);
			return bars;
		} finally {
			LibCStdlib.free(pcm.samples());
		}
	}

	/** A sound's waveform, worked out in the background. Null until it is ready, and when it can't be read. */
	public static float[] wave(String key, Supplier<byte[]> ogg) {
		float[] w = WAVES.get(key);
		if (w == null) {
			WAVES.put(key, LOADING);
			CompletableFuture.supplyAsync(() -> peaks(ogg.get()), dev.itemstudio.Workers.PREVIEWS)
				.whenComplete((bars, error) -> WAVES.put(key, bars == null ? NONE : bars));
			return null;
		}
		return w == LOADING || w == NONE ? null : w;
	}

	public static boolean waveLoading(String key) {
		return WAVES.get(key) == LOADING;
	}

	/** Forgets waveforms (the packs were scanned again). Ones still being worked out finish into the new list. */
	public static void clearWaves() {
		WAVES.clear();
	}

	/**
	 * Which of count files to play for key: the first one, then the next one each time the same thing is played
	 * again, so packs with several hit sounds can all be heard.
	 */
	public static synchronized int nextTurn(String key, int count) {
		if (count <= 0) return 0;
		lastTurn = key.equals(lastKey) ? (lastTurn + 1) % count : 0;
		lastKey = key;
		return lastTurn;
	}

	/** Stops what is playing and plays this sound file. key says what is playing (see isPlaying). */
	public static void play(String key, Supplier<byte[]> ogg) {
		playingKey = key;
		playingUntil = System.currentTimeMillis() + 400;
		PLAYER.execute(() -> {
			try {
				playNow(key, ogg.get());
			} catch (Throwable t) {
				dev.itemstudio.ItemStudioClient.LOG.debug("Could not play sound preview: {}", t.toString());
				playingUntil = 0;
			}
		});
	}

	/** True while the sound for key is playing. */
	public static boolean isPlaying(String key) {
		return key.equals(playingKey) && System.currentTimeMillis() < playingUntil;
	}

	public static void stop() {
		playingKey = null;
		playingUntil = 0;
		PLAYER.execute(() -> {
			try {
				stopNow();
			} catch (Throwable ignored) {
			}
		});
	}

	private static void playNow(String key, byte[] ogg) {
		stopNow();
		if (!key.equals(playingKey)) return; // something else was asked for meanwhile
		if (ALC10.alcGetCurrentContext() == 0L) {
			playingUntil = 0;
			return;
		}
		Pcm pcm = decode(ogg);
		if (pcm == null) {
			playingUntil = 0;
			return;
		}
		try {
			ShortBuffer samples = pcm.samples();
			int channels = pcm.channels();
			if (channels > 2) {
				// the sound device only takes mono or stereo: mix down to mono
				int frames = samples.remaining() / channels;
				ShortBuffer mono = MemoryUtil.memAllocShort(frames);
				for (int f = 0; f < frames; f++) {
					int sum = 0;
					for (int c = 0; c < channels; c++) sum += samples.get(f * channels + c);
					mono.put(f, (short) (sum / channels));
				}
				try {
					upload(mono, 1, pcm.rate());
				} finally {
					MemoryUtil.memFree(mono);
				}
			} else {
				upload(samples, channels, pcm.rate());
			}
			long millis = (long) (samples.remaining() / channels) * 1000L / pcm.rate();
			if (source != 0 && key.equals(playingKey)) playingUntil = System.currentTimeMillis() + millis + 100;
		} finally {
			LibCStdlib.free(pcm.samples());
		}
	}

	private static void upload(ShortBuffer samples, int channels, int rate) {
		AL10.alGetError();
		buffer = AL10.alGenBuffers();
		AL10.alBufferData(buffer, channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16, samples, rate);
		source = AL10.alGenSources();
		if (AL10.alGetError() != AL10.AL_NO_ERROR) {
			// every source is in use by the game, or the device is gone: skip this one
			stopNow();
			playingUntil = 0;
			return;
		}
		AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
		AL10.alSource3f(source, AL10.AL_POSITION, 0f, 0f, 0f);
		AL10.alSourcef(source, AL10.AL_GAIN, 1f);
		AL10.alSourcef(source, AL10.AL_PITCH, 1f);
		AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
		AL10.alSourcei(source, AL10.AL_BUFFER, buffer);
		AL10.alSourcePlay(source);
	}

	private static void stopNow() {
		if (source != 0) {
			if (ALC10.alcGetCurrentContext() != 0L && AL10.alIsSource(source)) {
				AL10.alSourceStop(source);
				AL10.alDeleteSources(source);
			}
			source = 0;
		}
		if (buffer != 0) {
			if (ALC10.alcGetCurrentContext() != 0L && AL10.alIsBuffer(buffer)) AL10.alDeleteBuffers(buffer);
			buffer = 0;
		}
	}
}
