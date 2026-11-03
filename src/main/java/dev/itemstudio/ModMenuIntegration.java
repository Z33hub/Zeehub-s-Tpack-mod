package dev.itemstudio;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.terraformersmc.modmenu.api.UpdateChannel;
import com.terraformersmc.modmenu.api.UpdateChecker;
import com.terraformersmc.modmenu.api.UpdateInfo;
import dev.itemstudio.ui.StudioScreen;

/**
 * Adds a settings button and an update badge to Item Studio in Mod Menu. The settings button opens the Item Studio
 * menu with its settings panel showing. Only loaded when Mod Menu is installed.
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return StudioScreen::withSettings;
	}

	@Override
	public UpdateChecker getUpdateChecker() {
		return () -> {
			UpdateCheck.Update update;
			try {
				update = UpdateCheck.start().get(20, java.util.concurrent.TimeUnit.SECONDS);
			} catch (Exception e) {
				return null;
			}
			if (update == null) return null;
			return new UpdateInfo() {
				@Override
				public boolean isUpdateAvailable() {
					return true;
				}

				@Override
				public String getDownloadLink() {
					return update.url();
				}

				@Override
				public UpdateChannel getUpdateChannel() {
					return UpdateChannel.RELEASE;
				}
			};
		};
	}
}
