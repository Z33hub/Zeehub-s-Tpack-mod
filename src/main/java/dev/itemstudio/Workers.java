package dev.itemstudio;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Background threads for Item Studio, kept at low priority so they never take time from the game's own frames.
 * BACKGROUND does one job at a time (pack scans, comparing packs, building the pack); PREVIEWS draws card pictures,
 * newest request first, so what you are looking at now is ready before cards you scrolled past.
 */
public final class Workers {
	public static final ExecutorService BACKGROUND = pool("Item Studio worker", 1, false);
	public static final ExecutorService PREVIEWS = pool("Item Studio previews", 2, true);

	private Workers() {
	}

	private static ExecutorService pool(String name, int threads, boolean newestFirst) {
		LinkedBlockingDeque<Runnable> queue = newestFirst ? new LinkedBlockingDeque<>() {
			@Override
			public boolean offer(Runnable r) {
				return offerFirst(r);
			}
		} : new LinkedBlockingDeque<>();
		ThreadPoolExecutor executor = new ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, queue, r -> {
			Thread t = new Thread(r, name);
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			return t;
		});
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}
}
