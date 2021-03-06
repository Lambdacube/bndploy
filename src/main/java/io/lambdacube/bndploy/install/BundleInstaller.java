package io.lambdacube.bndploy.install;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import org.ops4j.pax.tinybundles.core.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.lambdacube.bndploy.dirwatcher.DirWatcher;
import io.lambdacube.bndploy.dirwatcher.FileChangeListener;
import io.lambdacube.bndploy.install.BundleChecker.Action;

public final class BundleInstaller implements BundleActivator {

    private static final Logger LOGGER = LoggerFactory.getLogger(BundleInstaller.class);

    public final ConfigReader configReader = new ConfigReader();

    private BundleContext context;

    private BundleChecker bundleChecker;

    private Config config;

    private Map<File, DirWatcher> watchers = Maps.newConcurrentMap();

    @Override
    public void start(BundleContext context) throws Exception {
        this.context = context;
        config = configReader.getConfig();
        bundleChecker = new BundleChecker(context, config);
        LOGGER.info("Installing runtime bundles from " + Joiner.on(", ").join(config.runtimeDirs));

        deployRuntime(config.runtimeDirs);

        deployApplications(config.applicationDirs);

    }

    private void deployRuntime(ImmutableList<String> runtimeDirs) {
        for (String dir : runtimeDirs) {
            ImmutableList<Bundle> bundles = installDirectory(new File(dir));
            startBundles(bundles);
        }
    }

    private void deployApplications(ImmutableList<String> applicationDirs) {
        for (String dir : applicationDirs) {
            File fileDir = new File(dir);
            ImmutableList<Bundle> bundles = installDirectory(fileDir);
            startBundles(bundles);

            if (config.watchApplicationDirs) {
                FileChangeListener listener = new FileChangeListener() {

                    @Override
                    public void filesCreated(List<Path> pathes) {
                        List<Bundle> bundles = Lists.newArrayList();
                        for (Path path : pathes) {
                            File file = path.toFile();
                            if (file.isDirectory()) {
                                bundles.addAll(installDirectory(file));
                            }
                            else {
                                Bundle b = installOrUpdateBundle(file, false);
                                if (b != null) {
                                    bundles.add(b);
                                }
                            }
                        }
                        for (Bundle b : bundles) {
                            try {
                                b.start();
                            } catch (BundleException e) {
                                LOGGER.error("Error while starting bundle {}", b.getSymbolicName(), e);
                            }
                        }
                    }

                    @Override
                    public void filesUpdated(List<Path> pathes) {
                        for (Path path : pathes) {
                            File file = path.toFile();
                            if (file.isDirectory()) {
                                installDirectory(file);
                            } else {
                                installOrUpdateBundle(file, true);
                            }
                        }
                    }

                    @Override
                    public void filesDeleted(List<Path> pathes) {

                    }
                };

                watchers.put(fileDir, new DirWatcher(fileDir.toPath(), 1500, listener));
            }
        }

        if (config.watchApplicationDirs) {
            for (DirWatcher watcher : watchers.values()) {
                try {
                    watcher.start();
                } catch (IOException e) {
                    LOGGER.error("Couldn't start dirwatcher", e);
                }
            }
        }
    }

    private ImmutableList<Bundle> installDirectory(File dir) {
        if (!dir.exists()) {
            return ImmutableList.of();
        }
        ImmutableList.Builder<Bundle> bundlesBuilder = ImmutableList.builder();

        File[] jarFiles = dir.listFiles(new FilenameFilter() {

            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".jar");
            }
        });

        for (File file : jarFiles) {
            Bundle bundle = installOrUpdateBundle(file, false);
            if (bundle != null) {
                bundlesBuilder.add(bundle);
            }
        }
        File[] subDirs = dir.listFiles(new FileFilter() {

            @Override
            public boolean accept(File file) {
                return file.isDirectory();
            }
        });

        for (File subDir : subDirs) {
            bundlesBuilder.addAll(installDirectory(subDir));
        }

        return bundlesBuilder.build();
    }

    private Bundle installOrUpdateBundle(File file, boolean update) {
        try (JarFile jarFile = new JarFile(file)) {

            Action action = bundleChecker.getAction(jarFile, update);
            if (action == Action.NONE) {
                return null;
            }
            try (FileInputStream inputStream = new FileInputStream(file)) {

                Bundle bundle = null;
                String location = makeLocation(jarFile);
                switch (action) {
                case INSTALL:
                    LOGGER.info("Installing bundle {}", location);
                    bundle = context.installBundle(location, inputStream);
                    break;
                case UPDATE:
                    bundle = context.getBundle(location);
                    if (bundle != null) {
                        LOGGER.info("Updating bundle {}", location);
                        bundle.stop();
                        bundle.update(inputStream);
                        bundle.start();
                    }
                    else {
                        LOGGER.warn("Not updating core bundle {}", location);
                    }
                    break;
                case WRAP_AND_INSTALL:
                    LOGGER.info("Wrapping JAR {}", location);
                    InputStream wrappingStream = TinyBundles.bundle().read(inputStream)
                            .set("Bundle-SymbolicName", location)
                            .build(TinyBundles.withClassicBuilder());
                    bundle = context.installBundle(location, wrappingStream);
                    break;
                case STOP_FRAMEWORK:
                    LOGGER.info("Stopping the framework!");
                    context.getBundle(0).stop();
                default:
                    break;
                }
                return bundle;

            } catch (BundleException e) {
                LOGGER.error("Error while installing bundle at {}", file, e);
            }

        } catch (IOException e) {
            LOGGER.error("Exception while trying to install or update file: {}", file, e);
        }
        return null;
    }

    private void startBundles(Iterable<Bundle> bundles) {
        for (Bundle b : bundles) {
            try {
                b.start();
            } catch (BundleException e) {
                LOGGER.error("Couldn't start bundle {}", b.getSymbolicName(), e);
            }
        }
    }

    private String makeLocation(JarFile jarFile) throws IOException {
        StringBuilder buf = new StringBuilder();
        final Manifest manifest = jarFile.getManifest();
        String bsn = manifest.getMainAttributes().getValue("Bundle-SymbolicName");
        final String version = manifest.getMainAttributes().getValue("Bundle-Version");

        if (bsn != null) {
            bsn = BundleUtils.getBsn(bsn);
            buf.append(bsn);
            if (version != null) {
                buf.append(':');
                buf.append(version);
            }
        }
        else {
            buf.append(jarFile.getName());
        }
        return buf.toString();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        bundleChecker.dispose();

        if (config.watchApplicationDirs) {
            for (DirWatcher watcher : watchers.values()) {
                watcher.stop();
            }
        }
    }

}
