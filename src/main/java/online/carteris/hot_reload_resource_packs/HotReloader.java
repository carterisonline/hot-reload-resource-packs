package online.carteris.hot_reload_resource_packs;

import net.minecraft.client.MinecraftClient;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

public class HotReloader extends Thread {
    static Logger logger;
    static MinecraftClient minecraft_client;
    static Path resource_packs_path;
	static PathMatcher reject_non_packs;
	static long recent_ping;

    public void run() {
        logger.info("Watching for changes in {}", resource_packs_path);
        var default_fs = FileSystems.getDefault();
		// Some mods like respackopts generate files (resource.zip.rpo) in the
		// resource dir on reloading packs. Be conservative on what files we
		// trigger on.
		reject_non_packs = FileSystems.getDefault().getPathMatcher("glob:*.zip");

		// Track the latest reload time to avoid double events on (open,
		// modify), for systems that trigger these as unique separate
		// ENTRY_MODIFY
		recent_ping = System.currentTimeMillis();

        try (var watch_service = default_fs.newWatchService()) {
            // watch the resourcepacks folder for changes
            registerPath(watch_service, resource_packs_path);

            // recursively visit the preexisting directories and watch them for changes too
            Files.walkFileTree(resource_packs_path, new SimpleFileVisitor<>() {
               @Override
               public @NotNull FileVisitResult preVisitDirectory(Path dir, @NotNull BasicFileAttributes attrs) {
                   registerPath(watch_service, dir);
                   return FileVisitResult.CONTINUE;
               }
            });

            // watch for file events forever (until the game closes)
            while(true) {
                watchFileEvents(watch_service);
            }
        } catch (UnsupportedOperationException e) {
            logger.error("The filesystem doesn't support watching for file changes");
            throw new RuntimeException(e);
        } catch (IOException e) {
            logger.error("IO error while trying to engage filesystem watching: {}", e.getLocalizedMessage());
            throw new RuntimeException(e);
        }
    }

    void watchFileEvents(WatchService watch_service) {
        try {
            // a "key" represents a registered directory
            var watch_key = watch_service.take();
			// whether or not we will reload packs with this file event
			var reload = false;

            // handle file events that occur in one of our registered directories
            for (WatchEvent<?> event : watch_key.pollEvents()) {
                handleFileEvent(watch_service, watch_key, event);

				Boolean is_resource_pack = reject_non_packs.matches( ((WatchEvent<Path>) event).context() );
				if ( is_resource_pack )
					reload = true;

            }

            // let the watch service know that we're done handling events for this directory
            watch_key.reset();

			// Test if we just got here, is recent_ping within a seconds of the previous.
			if ( ( System.currentTimeMillis() - recent_ping ) < 1500 ) {
				reload = false;
			}
			recent_ping = System.currentTimeMillis();

			if ( reload ) {
				minecraft_client.reloadResources();
			}

        } catch (InterruptedException e) {
            logger.error("Got interrupted while trying to wait for file changes");
            throw new RuntimeException(e);
        }
    }

    void handleFileEvent(WatchService watch_service, WatchKey watch_key, WatchEvent<?> event) {
        // this looks weird, but we're essentially joining the watched path (.minecraft/resourcepacks/xyz) with the ...
        // ... name of the file that had an event occur. Trying to get the absolute path of the file on its own ...
        // ... using event.context().toAbsolutePath() will give us the wrong path for some reason.
        var path = ((Path) watch_key.watchable()).resolve(((WatchEvent<Path>) event).context()).toAbsolutePath();

        // register any new directories to be watched
        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && path.toFile().isDirectory()) {
            registerPath(watch_service, path);
        }
    }

    void registerPath(WatchService watch_service, Path path) {
        try {
            path.register(
                    watch_service,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE
            );
        } catch (IOException e) {
            logger.error("IO error while watching path ({}): {}", path, e.getLocalizedMessage());
            throw new RuntimeException(e);
        }
    }

    public HotReloader(MinecraftClient minecraft_client, Logger logger, Path resource_packs_path) {
        super();

        HotReloader.minecraft_client = minecraft_client;
        HotReloader.resource_packs_path = resource_packs_path;
        HotReloader.logger = logger;
    }
}
