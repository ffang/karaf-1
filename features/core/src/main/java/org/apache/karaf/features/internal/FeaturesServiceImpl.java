/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.features.internal;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Dictionary;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;

import org.apache.felix.utils.version.VersionRange;
import org.apache.felix.utils.version.VersionTable;
import org.apache.karaf.features.BundleInfo;
import org.apache.karaf.features.Conditional;
import org.apache.karaf.features.ConfigFileInfo;
import org.apache.karaf.features.ConfigInfo;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeatureEvent;
import org.apache.karaf.features.FeaturesListener;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.Repository;
import org.apache.karaf.features.RepositoryEvent;
import org.apache.karaf.features.Resolver;
import org.apache.karaf.util.collections.CopyOnWriteArrayIdentityList;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.FrameworkListener;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.Version;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.startlevel.BundleStartLevel;
import org.osgi.framework.wiring.BundleCapability;
import org.osgi.framework.wiring.BundleRequirement;
import org.osgi.framework.wiring.BundleRevision;
import org.osgi.framework.wiring.BundleWire;
import org.osgi.framework.wiring.BundleWiring;
import org.osgi.framework.wiring.FrameworkWiring;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.util.tracker.ServiceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static java.lang.String.format;
import static java.util.jar.JarFile.MANIFEST_NAME;

/**
 * The Features service implementation.
 * Adding a repository url will load the features contained in this repository and
 * create dummy sub shells.  When invoked, these commands will prompt the user for
 * installing the needed bundles.
 */
public class FeaturesServiceImpl implements FeaturesService {

    public static final String CONFIG_KEY = "org.apache.karaf.features.configKey";

    private static final Logger LOGGER = LoggerFactory.getLogger(FeaturesServiceImpl.class);

    private static final int KARAF_BUNDLE_START_LEVEL =
            Integer.parseInt(System.getProperty("karaf.startlevel.bundle", "80"));

    private BundleContext bundleContext;
    private ConfigurationAdmin configAdmin;
    private boolean respectStartLvlDuringFeatureStartup;
    private long resolverTimeout = 5000;
    private Set<URI> uris = new HashSet<URI>();
    private Map<URI, Repository> repositories = new ConcurrentHashMap<URI, Repository>();
    private Map<String, Map<String, Feature>> features;
    private Map<Feature, Set<Long>> installed = new HashMap<Feature, Set<Long>>();
    private String boot;
    private boolean bootFeaturesAsynchronous;
    private boolean bootFeaturesInstalled;
    private List<FeaturesListener> listeners = new CopyOnWriteArrayIdentityList<FeaturesListener>();
    private ThreadLocal<Repository> repo = new ThreadLocal<Repository>();
    private EventAdminListener eventAdminListener;
    private String blackList;

    public FeaturesServiceImpl() {
    }

    public BundleContext getBundleContext() {
        return bundleContext;
    }

    public void setBundleContext(BundleContext bundleContext) {
        this.bundleContext = bundleContext;
    }

    public ConfigurationAdmin getConfigAdmin() {
        return configAdmin;
    }

    public void setConfigAdmin(ConfigurationAdmin configAdmin) {
        this.configAdmin = configAdmin;
    }

    public void setRespectStartLvlDuringFeatureStartup(boolean respectStartLvlDuringFeatureStartup) {
        this.respectStartLvlDuringFeatureStartup = respectStartLvlDuringFeatureStartup;
    }

    public long getResolverTimeout() {
        return resolverTimeout;
    }

    public void setResolverTimeout(long resolverTimeout) {
        this.resolverTimeout = resolverTimeout;
    }

    public void registerListener(FeaturesListener listener) {
        listeners.add(listener);
        for (Repository repository : listRepositories()) {
            listener.repositoryEvent(new RepositoryEvent(repository, RepositoryEvent.EventType.RepositoryAdded, true));
        }
        for (Feature feature : listInstalledFeatures()) {
            listener.featureEvent(new FeatureEvent(feature, FeatureEvent.EventType.FeatureInstalled, true));
        }
    }

    public void unregisterListener(FeaturesListener listener) {
        listeners.remove(listener);
    }

    public void setUrls(String uris) throws URISyntaxException {
        String[] s = uris.split(",");
        for (String value : s) {
            value = value.trim();
            if (!value.isEmpty()) {
                this.uris.add(new URI(value));
            }
        }
    }

    public void setBoot(String boot) {
        this.boot = boot;
    }

    public void setBootFeaturesAsynchronous(boolean bootFeaturesAsynchronous) {
        this.bootFeaturesAsynchronous = bootFeaturesAsynchronous;
    }

    public void setBlackList(String blackList) {
        this.blackList = blackList;
    }

    /**
     * Validate repository.
     *
     * @param uri the features repository URI.
     */
    public void validateRepository(URI uri) throws Exception {
        FeatureValidationUtil.validate(uri);
    }

    /**
     * Register a features repository.
     *
     * @param uri the features repository URI.
     * @throws Exception in case of adding failure.
     */
    public void addRepository(URI uri) throws Exception {
        addRepository(uri, false);
    }

    /**
     * Register a features repository.
     *
     * @param uri
     * @param install
     * @throws Exception
     */
    public void addRepository(URI uri, boolean install) throws Exception {
        if (!repositories.containsKey(uri)) {
            RepositoryImpl repository = internalAddRepository(uri);
            saveState();
            if (install) {
                for (Feature feature : repository.getFeatures()) {
                    installFeature(feature, EnumSet.noneOf(Option.class));
                }
            }
        } else {
            refreshRepository(uri, install);
        }
    }

    /**
     * Add a features repository into the internal registry.
     *
     * @param uri the features repository URI.
     * @return the internal <code>RepositoryImpl</code> representation.
     * @throws Exception in case of adding failure.
     */
    protected RepositoryImpl internalAddRepository(URI uri) throws Exception {
        validateRepository(uri);
        RepositoryImpl repo = null;
        repo = new RepositoryImpl(uri, blackList);
        repositories.put(uri, repo);
        uris.add(uri);
        repo.load();
        if (repo.getName() == null) {
            LOGGER.warn("Feature repository doesn't have a name. The name will be mandatory in the next Karaf version.");
        }
        callListeners(new RepositoryEvent(repo, RepositoryEvent.EventType.RepositoryAdded, false));
        features = null;
        return repo;

    }

    /**
     * Refresh a features repository.
     *
     * @param uri the features repository URI.
     * @throws Exception in case of refresh failure.
     */
    protected void refreshRepository(URI uri) throws Exception {
        this.refreshRepository(uri, false);
    }

    /**
     * Refresh a features repository.
     *
     * @param uri     the features repository URI.
     * @param install if true, install all features in the features repository.
     * @throws Exception in case of refresh failure.
     */
    protected void refreshRepository(URI uri, boolean install) throws Exception {
        try {
            removeRepository(uri, install);
            addRepository(uri, install);
        } catch (Exception e) {
            //get chance to restore previous, fix for KARAF-4
            restoreRepository(uri);
            throw new Exception("Unable to refresh features repository " + uri, e);
        }
    }

    /**
     * Remove a features repository.
     *
     * @param uri the features repository URI.
     * @throws Exception in case of remove failure.
     */
    public void removeRepository(URI uri) throws Exception {
        this.removeRepository(uri, false);
    }

    /**
     * Remove a features repository.
     *
     * @param uri       the features repository URI.
     * @param uninstall if true, uninstall all features from the features repository.
     * @throws Exception in case of remove failure.
     */
    public void removeRepository(URI uri, boolean uninstall) throws Exception {
        if (repositories.containsKey(uri)) {
            if (uninstall) {
                Repository repository = repositories.get(uri);
                for (Feature feature : repository.getFeatures()) {
                    this.uninstallFeature(feature.getName(), feature.getVersion());
                }
            }
            internalRemoveRepository(uri);
            saveState();
        }
    }

    /**
     * Remove a features repository from the internal registry.
     *
     * @param uri the features repository URI.
     */
    public void internalRemoveRepository(URI uri) {
        Repository repo = repositories.remove(uri);
        this.repo.set(repo);
        callListeners(new RepositoryEvent(repo, RepositoryEvent.EventType.RepositoryRemoved, false));
        features = null;
    }

    /**
     * Restore a features repository.
     *
     * @param uri the features repository URI.
     * @throws Exception in case of restore failure.
     */
    public void restoreRepository(URI uri) throws Exception {
        repositories.put(uri, (RepositoryImpl) repo.get());
        callListeners(new RepositoryEvent(repo.get(), RepositoryEvent.EventType.RepositoryAdded, false));
        features = null;
    }

    /**
     * List the features repositories.
     *
     * @return the list of features repositories.
     */
    public Repository[] listRepositories() {
        // the constructor will iterate over ConcurrentHashMap without the risk of ConcurrentModificationException
        Collection<Repository> repos = new ArrayList<Repository>(repositories.values());
        return repos.toArray(new Repository[repos.size()]);
    }

    /**
     * Install a feature identified by a name.
     *
     * @param name the name of the feature.
     * @throws Exception in case of installation failure.
     */
    public void installFeature(String name) throws Exception {
        installFeature(name, FeatureImpl.DEFAULT_VERSION);
    }

    /**
     * Install a feature identified by a name, including installation options.
     *
     * @param name    the name of the feature.
     * @param options the installation options.
     * @throws Exception in case of installation failure.
     */
    public void installFeature(String name, EnumSet<Option> options) throws Exception {
        installFeature(name, FeatureImpl.DEFAULT_VERSION, options);
    }

    /**
     * Install a feature identified by a name and a version.
     *
     * @param name    the name of the feature.
     * @param version the version of the feature.
     * @throws Exception in case of installation failure.
     */
    public void installFeature(String name, String version) throws Exception {
        installFeature(name, version, EnumSet.noneOf(Option.class));
    }

    public void installFeature(String name, String version, EnumSet<Option> options) throws Exception {
        Feature f = getFeature(name, version);
        if (f == null) {
            throw new Exception("No feature named '" + name
                    + "' with version '" + version + "' available");
        }
        installFeature(f, options);
    }

    public void installFeature(Feature f, EnumSet<Option> options) throws Exception {
        installFeatures(Collections.singleton(f), options);
    }

    public void installFeatures(final Set<Feature> features, final EnumSet<Option> options) throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        try {
            executor.submit(new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    doInstallFeatures(features, options);
                    return null;
                }
            }).get();
        } catch (ExecutionException e) {
            Throwable t = e.getCause();
            if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else if (t instanceof Exception) {
                throw (Exception) t;
            } else {
                throw e;
            }
        } finally {
            executor.shutdown();
        }
    }

    protected void doInstallFeatures(Set<Feature> features, EnumSet<Option> options) throws Exception {
        final InstallationState state = new InstallationState();
        final InstallationState failure = new InstallationState();
        boolean verbose = options.contains(FeaturesService.Option.Verbose);
        Set<Bundle> bundlesToRefresh = null;
        try {
            // Install everything
            for (Feature f : features) {
                InstallationState s = new InstallationState();
                try {
                    doInstallFeature(s, f, verbose);

                    Set<Feature> installed = new HashSet<Feature>();
                    installed.addAll(this.installed.keySet());
                    installed.addAll(s.features.keySet());
                    installed.addAll(state.features.keySet());
                    installed.add(f);
                    for (Feature feature : installed) {
                        for (Conditional conditional : feature.getConditional()) {
                            if (dependenciesSatisfied(conditional.getCondition(), installed)) {
                                doInstallFeature(state, conditional.asFeature(feature.getName(), feature.getVersion()), verbose);
                            }
                        }
                    }
                    state.bundleInfos.putAll(s.bundleInfos);
                    state.bundles.addAll(s.bundles);
                    state.features.putAll(s.features);
                    state.installed.addAll(s.installed);
                } catch (Exception e) {
                    failure.bundles.addAll(s.bundles);
                    failure.features.putAll(s.features);
                    failure.installed.addAll(s.installed);
                    if (options.contains(Option.ContinueBatchOnFailure)) {
                        LOGGER.warn("Error when installing feature {}: {}", f.getName(), e);
                    } else {
                        throw e;
                    }
                }
            }
            // Find bundles to refresh
            boolean print = options.contains(Option.PrintBundlesToRefresh);
            boolean refresh = !options.contains(Option.NoAutoRefreshBundles);
            if (print || refresh) {
                bundlesToRefresh = findBundlesToRefresh();
                StringBuilder sb = new StringBuilder();
                for (Bundle b : bundlesToRefresh) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(b.getSymbolicName()).append(" (").append(b.getBundleId()).append(")");
                }
                LOGGER.debug("Bundles to refresh: {}", sb.toString());
                if (!bundlesToRefresh.isEmpty()) {
                    if (print) {
                        if (refresh) {
                            System.out.println("Refreshing bundles " + sb.toString());
                        } else {
                            System.out.println("The following bundles may need to be refreshed: " + sb.toString());
                        }
                    }
                    if (refresh) {
                        LOGGER.debug("Refreshing bundles: {}", sb.toString());
                        if (!options.contains(Option.Boot)) {
                            refreshPackages(bundlesToRefresh);
                        }
                    }
                }
            }
            // start all bundles sorted by startlvl if wished for
            List<Bundle> bundlesSortedByStartLvl = new ArrayList<Bundle>(state.bundles);
            if (respectStartLvlDuringFeatureStartup) {
                Collections.sort(bundlesSortedByStartLvl, new Comparator<Bundle>() {
                    @Override
                    public int compare(Bundle bundle, Bundle bundle1) {
                        return bundle.adapt(BundleStartLevel.class).getStartLevel()
                                - bundle1.adapt(BundleStartLevel.class).getStartLevel();
                    }
                });
            }
            for (Bundle b : bundlesSortedByStartLvl) {
                // do not start fragment bundles
                Dictionary d = b.getHeaders();
                String fragmentHostHeader = (String) d.get(Constants.FRAGMENT_HOST);
                if (fragmentHostHeader == null || fragmentHostHeader.trim().length() == 0) {
                    // do not start bundles that are persistently stopped
                    if (state.installed.contains(b)
                            || (b.getState() != Bundle.STARTING && b.getState() != Bundle.ACTIVE
                            && isBundlePersistentlyStarted(b))) {
                        // do no start bundles when user request it
                        Long bundleId = b.getBundleId();
                        BundleInfo bundleInfo = state.bundleInfos.get(bundleId);
                        if (bundleInfo == null || bundleInfo.isStart()) {
                            try {
                                if (!options.contains(Option.NoAutoStartBundles)) {
                                    b.start();
                                }
                            } catch (BundleException be) {
                                String msg = format("Could not start bundle %s in feature(s) %s: %s", b.getLocation(), getFeaturesContainingBundleList(b), be.getMessage());
                                throw new Exception(msg, be);
                            }
                        }
                    }
                }
            }
            // Clean up for batch
            if (!options.contains(Option.NoCleanIfFailure)) {
                failure.installed.removeAll(state.bundles);
                for (Bundle b : failure.installed) {
                    try {
                        b.uninstall();
                    } catch (Exception e2) {
                        // Ignore
                    }
                }
            }
            // Refresh bundles async for boot
            if (options.contains(Option.Boot) && bundlesToRefresh != null && !bundlesToRefresh.isEmpty()) {
                refreshPackagesAsync(bundlesToRefresh);
            }
        } catch (Exception e) {
            // cleanup on error
            if (!options.contains(Option.NoCleanIfFailure)) {
                // Uninstall everything
                for (Bundle b : state.installed) {
                    try {
                        b.uninstall();
                    } catch (Exception e2) {
                        // Ignore
                    }
                }
                for (Bundle b : failure.installed) {
                    try {
                        b.uninstall();
                    } catch (Exception e2) {
                        // Ignore
                    }
                }
            } else {
                // Force start of bundles so that they are flagged as persistently started
                for (Bundle b : state.installed) {
                    try {
                        b.start();
                    } catch (Exception e2) {
                        // Ignore
                    }
                }
            }
            // rethrow exception
            throw e;
        }
        for (Feature f : features) {
            callListeners(new FeatureEvent(f, FeatureEvent.EventType.FeatureInstalled, false));
        }
        for (Map.Entry<Feature, Set<Long>> e : state.features.entrySet()) {
            installed.put(e.getKey(), e.getValue());
        }
        saveState();
    }

    protected static class InstallationState {
        final Set<Bundle> installed = new HashSet<Bundle>();
        final Set<Bundle> bundles = new TreeSet<Bundle>();
        final Map<Long, BundleInfo> bundleInfos = new HashMap<Long, BundleInfo>();
        final Map<Feature, Set<Long>> features = new HashMap<Feature, Set<Long>>();
    }

    protected void doInstallFeature(InstallationState state, Feature feature, boolean verbose) throws Exception {
        LOGGER.debug("Installing feature " + feature.getName() + " " + feature.getVersion());
        if (verbose) {
            System.out.println("Installing feature " + feature.getName() + " " + feature.getVersion());
        }
        for (Feature dependency : feature.getDependencies()) {
            VersionRange range = FeatureImpl.DEFAULT_VERSION.equals(dependency.getVersion())
                    ? VersionRange.ANY_VERSION : new VersionRange(dependency.getVersion(), true, true);
            Feature fi = null;
            for (Feature f : installed.keySet()) {
                if (f.getName().equals(dependency.getName())) {
                    Version v = VersionTable.getVersion(f.getVersion());
                    if (range.contains(v)) {
                        if (fi == null || VersionTable.getVersion(fi.getVersion()).compareTo(v) < 0) {
                            fi = f;
                        }
                    }
                }
            }
            if (fi == null) {
                Map<String, Feature> avail = getFeatures().get(dependency.getName());
                if (avail != null) {
                    for (Feature f : avail.values()) {
                        Version v = VersionTable.getVersion(f.getVersion());
                        if (range.contains(v)) {
                            if (fi == null || VersionTable.getVersion(fi.getVersion()).compareTo(v) < 0) {
                                fi = f;
                            }
                        }
                    }
                }
            }
            if (fi == null) {
                throw new Exception("No feature named '" + dependency.getName()
                        + "' with version '" + dependency.getVersion() + "' available");
            }
            if (state.features.containsKey(fi)) {
                LOGGER.debug("Feature {} with version {} is already being installed", feature.getName(), feature.getVersion());
            } else {
                if (!(fi.getName().equals(feature.getName())
                        && fi.getVersion().equals(feature.getVersion()))) {
                    doInstallFeature(state, fi, verbose);
                }
            }
        }
		for (ConfigInfo config : feature.getConfigurations()) {
			String name = config.getName();
			Map<String, String> props = config.getProperties();

			String[] pid = parsePid(config.getName());
			Configuration cfg = findExistingConfiguration(configAdmin, pid[0],
					pid[1]);
			if (cfg == null) {
				
				Dictionary<String, String> cfgProps = convertToDict(config
						.getProperties());

				cfg = createConfiguration(configAdmin, pid[0], pid[1]);
				String key = createConfigurationKey(pid[0], pid[1]);
				cfgProps.put(CONFIG_KEY, key);
				cfg.update(cfgProps);
			} else if (config.isAppend()) {
				Dictionary<String, Object> properties = cfg.getProperties();
				for (Enumeration<String> propKeys = properties.keys(); propKeys
						.hasMoreElements();) {
					String key = propKeys.nextElement();
					// remove existing entry, since it's about appending.
					if (props.containsKey(key)) {
						props.remove(key);
					}
				}
				if (props.size() > 0) {
					// convert props to dictionary
					Dictionary<String, String> cfgProps = convertToDict(props);
					cfg.update(cfgProps);
				}
			}
        }
        for (ConfigFileInfo configFile : feature.getConfigurationFiles()) {
            installConfigurationFile(configFile.getLocation(),
                    configFile.getFinalname(), configFile.isOverride(), verbose);
        }
        Set<Long> bundles = new TreeSet<Long>();
        List<InstallResult> installResultList = new LinkedList<InstallResult>();
        for (BundleInfo bInfo : resolve(feature)) {
            InstallResult result = installBundleIfNeeded(state, bInfo, verbose);
            if (!result.isPreviouslyInstalled()) {
                //only associate the bundles installed by this feature
                //so that when uninstall this feature, won't uninstall bundles
                //installed by other features or from the startup.properties
                bundles.add(result.getBundle().getBundleId());
                state.bundleInfos.put(result.getBundle().getBundleId(), bInfo);
                installResultList.add(result);
            }
        }
        for (InstallResult result : installResultList) {
            if (!result.isPreviouslyInstalled()) {
                startBundleIfNeeded(result.getBundle(), result.getStartLevel());
            }
        }
        
        for (BundleInfo bInfo : feature.getBundles()) {
            Bundle bundle = isBundleInstalled(bInfo);
            if (bundle != null && !bundles.contains(bundle.getBundleId())) {
                bundles.add(bundle.getBundleId());
            }
        }
        state.features.put(feature, bundles);
    }
    
    protected Bundle isBundleInstalled(BundleInfo bundleInfo) throws IOException, BundleException {
        InputStream is;
        String bundleLocation = bundleInfo.getLocation();
        LOGGER.debug("Checking " + bundleLocation);
        try {
            is = new BufferedInputStream(new URL(bundleLocation).openStream());
        } catch (RuntimeException e) {
            LOGGER.error(e.getMessage());
            throw e;
        }
        try {
            is.mark(256 * 1024);
            JarInputStream jar = new JarInputStream(is);
            Manifest m = jar.getManifest();
            if (m == null) {
                ZipEntry entry;
                while ((entry = jar.getNextEntry()) != null) {
                    if (MANIFEST_NAME.equals(entry.getName())) {
                        m = new Manifest(jar);
                        break;
                    }
                }
                if (m == null) {
                    throw new BundleException("Manifest not present in the zip " + bundleLocation);
                }
            }
            String sn = m.getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME);
            if (sn == null) {
                throw new BundleException("Jar is not a bundle, no Bundle-SymbolicName " + bundleLocation);
            }
            // remove attributes from the symbolic name (like ;blueprint.graceperiod:=false suffix)
            int attributeIndexSep = sn.indexOf(';');
            if (attributeIndexSep != -1) {
                sn = sn.substring(0, attributeIndexSep);
            }
            String vStr = m.getMainAttributes().getValue(Constants.BUNDLE_VERSION);
            Version v = vStr == null ? Version.emptyVersion : Version.parseVersion(vStr);
            for (Bundle b : bundleContext.getBundles()) {
                if (b.getSymbolicName() != null && b.getSymbolicName().equals(sn)) {
                    vStr = (String) b.getHeaders().get(Constants.BUNDLE_VERSION);
                    Version bv = vStr == null ? Version.emptyVersion : Version.parseVersion(vStr);
                    if (v.equals(bv)) {
                        LOGGER.debug("Found installed bundle: " + b);
                        return b;
                    }
                }
            }
            return null;
        } finally {
            is.close();
        }
        
    }

    private Dictionary<String, String> convertToDict(Map<String, String> props) {
        Dictionary<String, String> cfgProps = new Hashtable<String, String>();
        for (Entry<String, String> property : props.entrySet()) {
            cfgProps.put(property.getKey(), property.getValue());
        }
        return cfgProps;
    }

    private String createConfigurationKey(String pid, String factoryPid) {
        return factoryPid == null ? pid : pid + "-" + factoryPid;
    }

    protected List<BundleInfo> resolve(Feature feature) throws Exception {
        String resolver = feature.getResolver();
        // If no resolver is specified, we expect a list of uris
        if (resolver == null || resolver.length() == 0) {
            return feature.getBundles();
        }
        boolean optional = false;
        if (resolver.startsWith("(") && resolver.endsWith(")")) {
            resolver = resolver.substring(1, resolver.length() - 1);
            optional = true;
        }
        // Else, find the resolver
        String filter = "(&(" + Constants.OBJECTCLASS + "=" + Resolver.class.getName() + ")(name=" + resolver + "))";
        ServiceTracker tracker = new ServiceTracker(bundleContext, FrameworkUtil.createFilter(filter), null);
        tracker.open();
        try {
            if (optional) {
                Resolver r = (Resolver) tracker.getService();
                if (r != null) {
                    return r.resolve(feature);
                } else {
                    LOGGER.debug("Optional resolver '" + resolver + "' not found, using the default resolver");
                    return feature.getBundles();
                }
            } else {
                Resolver r = (Resolver) tracker.waitForService(resolverTimeout);
                if (r == null) {
                    throw new Exception("Unable to find required resolver '" + resolver + "'");
                }
                return r.resolve(feature);
            }
        } finally {
            tracker.close();
        }
    }

    protected Set<Bundle> findBundlesToRefresh() {
        Set<Bundle> toRefresh = new HashSet<Bundle>();
        Set<Bundle> bundles = new HashSet<Bundle>(Arrays.asList(bundleContext.getBundles()));
        findBundlesWithOptionalPackagesToRefresh(bundles, toRefresh);
        findBundlesWithFragmentsToRefresh(bundles, toRefresh);
        return toRefresh;
    }

    protected void findBundlesWithFragmentsToRefresh(Collection<Bundle> allBundles, Set<Bundle> toRefresh) {
        if (toRefresh.isEmpty()) {
            return;
        }
        Set<Bundle> bundles = new HashSet<Bundle>(allBundles);
        bundles.removeAll(toRefresh);
        if (bundles.isEmpty()) {
            return;
        }
        for (Bundle bundle : new ArrayList<Bundle>(toRefresh)) {
            BundleRevision rev = bundle.adapt(BundleRevision.class);
            if (rev != null) {
                for (BundleRequirement req : rev.getDeclaredRequirements(null)) {
                    if (BundleRevision.HOST_NAMESPACE.equals(req.getNamespace())) {
                        for (Bundle hostBundle : bundles) {
                            if (!toRefresh.contains(hostBundle)) {
                                BundleRevision hostRev = hostBundle.adapt(BundleRevision.class);
                                if (hostRev != null) {
                                    for (BundleCapability cap : hostRev.getDeclaredCapabilities(null)) {
                                        if (req.matches(cap)) {
                                            toRefresh.add(hostBundle);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    protected void findBundlesWithOptionalPackagesToRefresh(Collection<Bundle> allBundles, Set<Bundle> toRefresh) {
        // First pass: include all bundles contained in these features
        if (toRefresh.isEmpty()) {
            return;
        }
        Set<Bundle> bundles = new HashSet<Bundle>(allBundles);
        bundles.removeAll(toRefresh);
        if (bundles.isEmpty()) {
            return;
        }
        // Second pass: for each bundle, check if there is any unresolved optional package that could be resolved
        for (Bundle bundle : bundles) {
            matchBundleWithOptionalImport(bundle, toRefresh);
        }
    }

    private void matchBundleWithOptionalImport(Bundle bundle, Set<Bundle> toRefresh) {
        BundleRevision revision = bundle.adapt(BundleRevision.class);
        for (BundleRequirement req : revision.getDeclaredRequirements(PackageNamespace.PACKAGE_NAMESPACE)) {
            if (PackageNamespace.RESOLUTION_OPTIONAL.equals(req.getDirectives().get(PackageNamespace.REQUIREMENT_RESOLUTION_DIRECTIVE))) {

                // Find the wire for this optional package import
                BundleWiring wiring = bundle.adapt(BundleWiring.class);
                BundleWire reqwire = null;
                if (wiring != null) {
                    for (BundleWire wire : wiring.getRequiredWires(PackageNamespace.PACKAGE_NAMESPACE)) {
                        if (req.equals(wire.getRequirement())) {
                            BundleCapability cap = wire.getCapability();
                            BundleRevision provider = wire.getProvider();
                            LOGGER.debug("Optional requirement {} from {} wires to capability {} provided by {}", req, bundle, cap, provider);
                            reqwire = wire;
                            break;
                        }
                    }
                }

                // If the requirement is already wired we don't need to do anything
                // the refresh algorithm will compute the transitive graph of refresh candidates
                if (reqwire == null) {

                    // Compute the set of possible providers
                    for (Bundle provider : toRefresh) {
                        BundleRevision providerRev = provider.adapt(BundleRevision.class);
                        if (providerRev != null) {
                            for (BundleCapability cap : providerRev.getDeclaredCapabilities(PackageNamespace.PACKAGE_NAMESPACE)) {
                                if (req.matches(cap)) {
                                    LOGGER.info("Found possible provider for unwired optional requirement {} from {}: {}", req, bundle, provider);
                                    toRefresh.add(bundle);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    protected static class InstallResult {

        private final boolean previouslyInstalled;
        private final Bundle bundle;
        private final int startLevel;

        protected InstallResult(boolean previouslyInstalled, Bundle bundle, int startLevel) {
            this.previouslyInstalled = previouslyInstalled;
            this.bundle = bundle;
            this.startLevel = startLevel;
        }

        public boolean isPreviouslyInstalled() {
            return previouslyInstalled;
        }

        public Bundle getBundle() {
            return bundle;
        }

        public int getStartLevel() {
            return startLevel;
        }

    }

    protected InstallResult installBundleIfNeeded(InstallationState state, BundleInfo bundleInfo, boolean verbose) throws IOException, BundleException {
        InputStream is;
        String bundleLocation = bundleInfo.getLocation();
        LOGGER.debug("Checking " + bundleLocation);
        try {
            is = new BufferedInputStream(new URL(bundleLocation).openStream());
        } catch (RuntimeException e) {
            LOGGER.error(e.getMessage());
            throw e;
        }
        try {
            is.mark(256 * 1024);
            JarInputStream jar = new JarInputStream(is);
            Manifest m = jar.getManifest();
            if (m == null) {
                ZipEntry entry;
                while ((entry = jar.getNextEntry()) != null) {
                    if (MANIFEST_NAME.equals(entry.getName())) {
                        m = new Manifest(jar);
                        break;
                    }
                }
                if (m == null) {
                    throw new BundleException("Manifest not present in the zip " + bundleLocation);
                }
            }
            String sn = m.getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME);
            if (sn == null) {
                throw new BundleException("Jar is not a bundle, no Bundle-SymbolicName " + bundleLocation);
            }
            // remove attributes from the symbolic name (like ;blueprint.graceperiod:=false suffix)
            int attributeIndexSep = sn.indexOf(';');
            if (attributeIndexSep != -1) {
                sn = sn.substring(0, attributeIndexSep);
            }
            String vStr = m.getMainAttributes().getValue(Constants.BUNDLE_VERSION);
            Version v = vStr == null ? Version.emptyVersion : Version.parseVersion(vStr);
            for (Bundle b : bundleContext.getBundles()) {
                if (b.getSymbolicName() != null && b.getSymbolicName().equals(sn)) {
                    vStr = (String) b.getHeaders().get(Constants.BUNDLE_VERSION);
                    Version bv = vStr == null ? Version.emptyVersion : Version.parseVersion(vStr);
                    if (v.equals(bv)) {
                        LOGGER.debug("Found installed bundle: " + b);
                        if (verbose) {
                            System.out.println("Found installed bundle: " + b);
                        }
                        state.bundles.add(b);
                        return new InstallResult(true, b, 0);
                    }
                }
            }
            try {
                is.reset();
            } catch (IOException e) {
                is.close();
                is = new BufferedInputStream(new URL(bundleLocation).openStream());
            }
            LOGGER.debug("Installing bundle " + bundleLocation);
            if (verbose) {
                System.out.println("Installing bundle " + bundleLocation);
            }
            Bundle b = getBundleContext().installBundle(bundleLocation, is);

            state.bundles.add(b);
            state.installed.add(b);
            return new InstallResult(false, b, bundleInfo.getStartLevel());
        } finally {
            is.close();
        }
    }

    private void startBundleIfNeeded(Bundle bundle, int startLevel) {
        if (startLevel > 0) {
            bundle.adapt(BundleStartLevel.class).setStartLevel(startLevel);
        }
    }

    private boolean isBundlePersistentlyStarted(Bundle bundle) {
        return bundle.adapt(BundleStartLevel.class).isPersistentlyStarted();
    }

    public void installConfigurationFile(String fileLocation, String finalname, boolean override, boolean verbose) throws IOException {
        LOGGER.debug("Checking configuration file " + fileLocation);
        if (verbose) {
            System.out.println("Checking configuration file " + fileLocation);
        }

        String basePath = System.getProperty("karaf.base");

        if (finalname.indexOf("${") != -1) {
            //remove any placeholder or variable part, this is not valid.
            int marker = finalname.indexOf("}");
            finalname = finalname.substring(marker + 1);
        }

        finalname = basePath + File.separator + finalname;

        File file = new File(finalname);
        if (file.exists() && !override) {
            LOGGER.debug("configFile already exist, don't override it");
            return;
        }

        InputStream is = null;
        FileOutputStream fop = null;
        try {
            is = new BufferedInputStream(new URL(fileLocation).openStream());

            if (!file.exists()) {
                File parentFile = file.getParentFile();
                if (parentFile != null) {
                    parentFile.mkdirs();
                }
                file.createNewFile();
            }

            fop = new FileOutputStream(file);

            int bytesRead = 0;
            byte[] buffer = new byte[1024];

            while ((bytesRead = is.read(buffer)) != -1) {
                fop.write(buffer, 0, bytesRead);
            }
        } catch (RuntimeException e) {
            LOGGER.error(e.getMessage());
            throw e;
        } catch (MalformedURLException e) {
            LOGGER.error(e.getMessage());
            throw e;
        } finally {
            if (is != null)
                is.close();
            if (fop != null) {
                fop.flush();
                fop.close();
            }
        }

    }

    public void uninstallFeature(String name) throws Exception {
        uninstallFeature(name, EnumSet.noneOf(Option.class));
    }

    public void uninstallFeature(String name, EnumSet<Option> options) throws Exception {
        List<String> versions = new ArrayList<String>();
        for (Feature f : installed.keySet()) {
            if (name.equals(f.getName())) {
                versions.add(f.getVersion());
            }
        }
        if (versions.size() == 0) {
            throw new Exception("Feature named '" + name + "' is not installed");
        } else if (versions.size() > 1) {
            StringBuilder sb = new StringBuilder();
            sb.append("Feature named '").append(name).append("' has multiple versions installed (");
            for (int i = 0; i < versions.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(versions.get(i));
            }
            sb.append("). Please specify the version to uninstall.");
            throw new Exception(sb.toString());
        }
        uninstallFeature(name, versions.get(0), options);
    }

    public void uninstallFeature(String name, String version) throws Exception {
        uninstallFeature(name, version, EnumSet.noneOf(Option.class));
    }

    public void uninstallFeature(String name, String version, EnumSet<Option> options) throws Exception {
        Feature feature = getFeature(name, version);
        if (feature == null || !installed.containsKey(feature)) {
            throw new Exception("Feature named '" + name
                    + "' with version '" + version + "' is not installed");
        }
        boolean verbose = options != null && options.contains(Option.Verbose);
        boolean refresh = options == null || !options.contains(Option.NoAutoRefreshBundles);
        if (verbose) {
            System.out.println("Uninstalling feature " + feature.getName() + " " + feature.getVersion());
        }
        // Grab all the bundles installed by this feature
        // and remove all those who will still be in use.
        // This gives this list of bundles to uninstall.
        Set<Long> bundles = installed.remove(feature);

        // Also remove bundles installed as conditionals
        for (Conditional conditional : feature.getConditional()) {
            Set<Long> ids = installed.remove(conditional.asFeature(feature.getName(), feature.getVersion()));
            if (ids != null) {
                bundles.addAll(ids);
            }
        }
        // Verify all other conditionals
        for (Feature dep : new ArrayList<Feature>(installed.keySet())) {
            Feature f = getFeature(dep.getName(), dep.getVersion());
            if (f != null) {
                for (Conditional conditional : f.getConditional()) {
                    if (!dependenciesSatisfied(conditional.getCondition(), installed.keySet())) {
                        Set<Long> ids = installed.remove(conditional.asFeature(f.getName(), f.getVersion()));
                        if (ids != null) {
                            bundles.addAll(ids);
                        }
                    }
                }
            }
        }

        for (Set<Long> b : installed.values()) {
            bundles.removeAll(b);
        }
        for (long bundleId : bundles) {
            Bundle b = getBundleContext().getBundle(bundleId);
            if (b != null) {
                b.uninstall();
            }
        }
        if (refresh) {
            if (verbose) {
                System.out.println("Refreshing packages");
            }
            refreshPackages(null);
        }
        callListeners(new FeatureEvent(feature, FeatureEvent.EventType.FeatureUninstalled, false));
        saveState();
    }

    public Feature[] listFeatures() throws Exception {
        Collection<Feature> features = new ArrayList<Feature>();
        for (Map<String, Feature> featureWithDifferentVersion : getFeatures().values()) {
            for (Feature f : featureWithDifferentVersion.values()) {
                features.add(f);
            }
        }
        return features.toArray(new Feature[features.size()]);
    }

    public Feature[] listInstalledFeatures() {
        Set<Feature> result = installed.keySet();
        return result.toArray(new Feature[result.size()]);
    }

    public boolean isInstalled(Feature f) {
        return installed.containsKey(f);
    }

    public Feature getFeature(String name) throws Exception {
        return getFeature(name, FeatureImpl.DEFAULT_VERSION);
    }

    public Feature getFeature(String name, String version) throws Exception {
        if (version != null) {
            version = version.trim();
        }
        Map<String, Feature> versions = getFeatures().get(name);
        if (versions == null || versions.isEmpty()) {
            return null;
        } else {
            Feature feature = versions.get(version);
            if (feature == null) {
                if (FeatureImpl.DEFAULT_VERSION.equals(version)) {
                    Version latest = new Version(cleanupVersion(version));
                    for (String available : versions.keySet()) {
                        Version availableVersion = new Version(cleanupVersion(available));
                        if (availableVersion.compareTo(latest) > 0) {
                            feature = versions.get(available);
                            latest = availableVersion;
                        }
                    }
                } else {
                    Version latest = new Version(cleanupVersion(FeatureImpl.DEFAULT_VERSION));
                    VersionRange versionRange = new VersionRange(version, true, true);
                    for (String available : versions.keySet()) {
                        Version availableVersion = new Version(cleanupVersion(available));
                        if (availableVersion.compareTo(latest) > 0 && versionRange.contains(availableVersion)) {
                            feature = versions.get(available);
                            latest = availableVersion;
                        }
                    }
                }
            }
            return feature;
        }
    }

    protected Map<String, Map<String, Feature>> getFeatures() throws Exception {
        if (features == null) {
            //the outer map's key is feature name, the inner map's key is feature version
            Map<String, Map<String, Feature>> map = new HashMap<String, Map<String, Feature>>();
            // Two phase load:
            // * first load dependent repositories
            for (; ; ) {
                boolean newRepo = false;
                for (Repository repo : listRepositories()) {
                    for (URI uri : repo.getRepositories()) {
                        if (!repositories.containsKey(uri)) {
                            internalAddRepository(uri);
                            newRepo = true;
                        }
                    }
                }
                if (!newRepo) {
                    break;
                }
            }
            // * then load all features
            for (Repository repo : repositories.values()) {
                for (Feature f : repo.getFeatures()) {
                    if (map.get(f.getName()) == null) {
                        Map<String, Feature> versionMap = new HashMap<String, Feature>();
                        versionMap.put(f.getVersion(), f);
                        map.put(f.getName(), versionMap);
                    } else {
                        map.get(f.getName()).put(f.getVersion(), f);
                    }
                }
            }
            features = map;
        }
        return features;
    }

    public void start() throws Exception {
        // Register EventAdmin listener
        EventAdminListener listener = null;
        try {
            getClass().getClassLoader().loadClass("org.osgi.service.event.EventAdmin");
            listener = new EventAdminListener(bundleContext);
        } catch (Throwable t) {
            // Ignore, if the EventAdmin package is not available, just don't use it
            LOGGER.debug("EventAdmin package is not available, just don't use it");
        }
        this.eventAdminListener = listener;
        // Load State
        if (!loadState()) {
            if (uris != null) {
                for (URI uri : uris) {
                    try {
                        internalAddRepository(uri);
                    } catch (Exception e) {
                        LOGGER.warn(format("Unable to add features repository %s at startup", uri), e);
                    }
                }
            }
            saveState();
        }
        // Install boot features
        if (boot != null && !bootFeaturesInstalled) {
            if (bootFeaturesAsynchronous) {
                new Thread() {
                    public void run() {
                        // splitting the features
                        String[] list = boot.split(",");
                        Set<Feature> features = new LinkedHashSet<Feature>();
                        for (String f : list) {
                            f = f.trim();
                            if (f.length() > 0) {
                                String featureVersion = null;

                                // first we split the parts of the feature string to gain access to the version info
                                // if specified
                                String[] parts = f.split(";");
                                String featureName = parts[0];
                                for (String part : parts) {
                                    // if the part starts with "version=" it contains the version info
                                    if (part.startsWith(FeatureImpl.VERSION_PREFIX)) {
                                        featureVersion = part.substring(FeatureImpl.VERSION_PREFIX.length());
                                    }
                                }

                                if (featureVersion == null) {
                                    // no version specified - use default version
                                    featureVersion = FeatureImpl.DEFAULT_VERSION;
                                }

                                try {
                                    // try to grab specific feature version
                                    Feature feature = getFeature(featureName, featureVersion);
                                    if (feature != null) {
                                        features.add(feature);
                                    } else {
                                        LOGGER.error("Error installing boot feature " + f + ": feature not found");
                                    }
                                } catch (Exception e) {
                                    LOGGER.error("Error installing boot feature " + f, e);
                                }
                            }
                        }
                        try {
                            installFeatures(features, EnumSet.of(Option.NoCleanIfFailure, Option.ContinueBatchOnFailure, Option.Boot));
                        } catch (Exception e) {
                            LOGGER.error("Error installing boot features", e);
                        }
                        bootFeaturesInstalled = true;
                        saveState();
                    }
                }.start();
            } else {
                // splitting the features
                String[] list = boot.split(",");
                Set<Feature> features = new LinkedHashSet<Feature>();
                for (String f : list) {
                    f = f.trim();
                    if (f.length() > 0) {
                        String featureVersion = null;

                        // first we split the parts of the feature string to gain access to the version info
                        // if specified
                        String[] parts = f.split(";");
                        String featureName = parts[0];
                        for (String part : parts) {
                            // if the part starts with "version=" it contains the version info
                            if (part.startsWith(FeatureImpl.VERSION_PREFIX)) {
                                featureVersion = part.substring(FeatureImpl.VERSION_PREFIX.length());
                            }
                        }

                        if (featureVersion == null) {
                            // no version specified - use default version
                            featureVersion = FeatureImpl.DEFAULT_VERSION;
                        }

                        try {
                            // try to grab specific feature version
                            Feature feature = getFeature(featureName, featureVersion);
                            if (feature != null) {
                                features.add(feature);
                            } else {
                                LOGGER.error("Error installing boot feature " + f + ": feature not found");
                            }
                        } catch (Exception e) {
                            LOGGER.error("Error installing boot feature " + f, e);
                        }
                    }
                }
                try {
                    installFeatures(features, EnumSet.of(Option.NoCleanIfFailure, Option.ContinueBatchOnFailure, Option.Boot));
                } catch (Exception e) {
                    LOGGER.error("Error installing boot features", e);
                }
                bootFeaturesInstalled = true;
                saveState();
            }
        }
    }

    public void stop() throws Exception {
        while (!repositories.isEmpty()) {
            internalRemoveRepository(repositories.keySet().iterator().next());
        }
    }

    protected void refreshPackages(Collection<Bundle> bundles) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);
        FrameworkWiring fw = bundleContext.getBundle(0).adapt(FrameworkWiring.class);
        fw.refreshBundles(bundles, new FrameworkListener() {
            @Override
            public void frameworkEvent(FrameworkEvent event) {
                if (event.getType() == FrameworkEvent.ERROR) {
                    LOGGER.error("Framework error", event.getThrowable());
                }
                latch.countDown();
            }
        });
        latch.await();
    }

    protected void refreshPackagesAsync(Collection<Bundle> bundles) throws InterruptedException {
        FrameworkWiring fw = bundleContext.getBundle(0).adapt(FrameworkWiring.class);
        fw.refreshBundles(bundles);
    }

    protected String[] parsePid(String pid) {
        int n = pid.indexOf('-');
        if (n > 0) {
            String factoryPid = pid.substring(n + 1);
            pid = pid.substring(0, n);
            return new String[]{pid, factoryPid};
        } else {
            return new String[]{pid, null};
        }
    }

    protected Configuration createConfiguration(ConfigurationAdmin configurationAdmin,
                                                String pid, String factoryPid) throws IOException, InvalidSyntaxException {
        if (factoryPid != null) {
            return configurationAdmin.createFactoryConfiguration(pid, null);
        } else {
            return configurationAdmin.getConfiguration(pid, null);
        }
    }

    protected Configuration findExistingConfiguration(ConfigurationAdmin configurationAdmin,
                                                      String pid, String factoryPid) throws IOException, InvalidSyntaxException {
        String filter;
        if (factoryPid == null) {
            filter = "(" + Constants.SERVICE_PID + "=" + pid + ")";
        } else {
            String key = createConfigurationKey(pid, factoryPid);
            filter = "(" + CONFIG_KEY + "=" + key + ")";
        }
        Configuration[] configurations = configurationAdmin.listConfigurations(filter);
        if (configurations != null && configurations.length > 0) {
            return configurations[0];
        }
        return null;
    }

    protected void saveState() {
        try {
            File file = bundleContext.getDataFile("FeaturesServiceState.properties");
            Properties props = new Properties();
            saveSet(props, "repositories.", repositories.keySet());
            saveMap(props, "features.", installed);
            props.put("bootFeaturesInstalled", Boolean.toString(bootFeaturesInstalled));
            OutputStream os = new FileOutputStream(file);
            try {
                props.store(new FileOutputStream(file), "FeaturesService State");
            } finally {
                os.close();
            }
        } catch (Exception e) {
            LOGGER.error("Error persisting FeaturesService state", e);
        }
    }

    protected boolean loadState() {
        try {
            File file = bundleContext.getDataFile("FeaturesServiceState.properties");
            if (!file.exists()) {
                return false;
            }
            Properties props = new Properties();
            InputStream is = new FileInputStream(file);
            try {
                props.load(is);
            } finally {
                is.close();
            }
            Set<URI> repositories = loadSet(props, "repositories.");
            for (URI repo : repositories) {
                try {
                    internalAddRepository(repo);
                } catch (Exception e) {
                    LOGGER.warn(format("Unable to add features repository %s at startup", repo), e);
                }
            }
            installed = loadMap(props, "features.");
            for (Feature f : installed.keySet()) {
                callListeners(new FeatureEvent(f, FeatureEvent.EventType.FeatureInstalled, true));
            }
            bootFeaturesInstalled = Boolean.parseBoolean((String) props.get("bootFeaturesInstalled"));
            return true;
        } catch (Exception e) {
            LOGGER.error("Error loading FeaturesService state", e);
        }
        return false;
    }

    protected void saveSet(Properties props, String prefix, Set<URI> set) {
        List<URI> l = new ArrayList<URI>(set);
        props.clear();
        props.put(prefix + "count", Integer.toString(l.size()));
        for (int i = 0; i < l.size(); i++) {
            props.put(prefix + "item." + i, l.get(i).toString());
        }
    }

    protected Set<URI> loadSet(Properties props, String prefix) {
        Set<URI> l = new HashSet<URI>();
        String countStr = (String) props.get(prefix + "count");
        if (countStr != null) {
            int count = Integer.parseInt(countStr);
            for (int i = 0; i < count; i++) {
                l.add(URI.create((String) props.get(prefix + "item." + i)));
            }
        }
        return l;
    }

    protected void saveMap(Properties props, String prefix, Map<Feature, Set<Long>> map) {
        for (Map.Entry<Feature, Set<Long>> entry : map.entrySet()) {
            Feature key = entry.getKey();
            String val = createValue(entry.getValue());
            props.put(prefix + key.toString(), val);
        }
    }

    protected Map<Feature, Set<Long>> loadMap(Properties props, String prefix) {
        Map<Feature, Set<Long>> map = new HashMap<Feature, Set<Long>>();
        for (Enumeration e = props.propertyNames(); e.hasMoreElements(); ) {
            String key = (String) e.nextElement();
            if (key.startsWith(prefix)) {
                String val = (String) props.get(key);
                Set<Long> set = readValue(val);
                map.put(FeatureImpl.valueOf(key.substring(prefix.length())), set);
            }
        }
        return map;
    }

    protected String createValue(Set<Long> set) {
        StringBuilder sb = new StringBuilder();
        for (long i : set) {
            if (sb.length() > 0) {
                sb.append(",");
            }
            sb.append(i);
        }
        return sb.toString();
    }

    protected Set<Long> readValue(String val) {
        Set<Long> set = new HashSet<Long>();
        if (val != null && val.length() != 0) {
            for (String str : val.split(",")) {
                set.add(Long.parseLong(str));
            }
        }
        return set;
    }

    protected void callListeners(FeatureEvent event) {
        if (eventAdminListener != null) {
            eventAdminListener.featureEvent(event);
        }
        for (FeaturesListener listener : listeners) {
            listener.featureEvent(event);
        }
    }

    protected void callListeners(RepositoryEvent event) {
        if (eventAdminListener != null) {
            eventAdminListener.repositoryEvent(event);
        }
        for (FeaturesListener listener : listeners) {
            listener.repositoryEvent(event);
        }
    }

    static Pattern fuzzyVersion = Pattern.compile("(\\d+)(\\.(\\d+)(\\.(\\d+))?)?([^a-zA-Z0-9](.*))?",
            Pattern.DOTALL);
    static Pattern fuzzyModifier = Pattern.compile("(\\d+[.-])*(.*)",
            Pattern.DOTALL);

    /**
     * Clean up version parameters. Other builders use more fuzzy definitions of
     * the version syntax. This method cleans up such a version to match an OSGi
     * version.
     *
     * @param version
     * @return
     */
    static public String cleanupVersion(String version) {
        Matcher m = fuzzyVersion.matcher(version);
        if (m.matches()) {
            StringBuffer result = new StringBuffer();
            String d1 = m.group(1);
            String d2 = m.group(3);
            String d3 = m.group(5);
            String qualifier = m.group(7);

            if (d1 != null) {
                result.append(d1);
                if (d2 != null) {
                    result.append(".");
                    result.append(d2);
                    if (d3 != null) {
                        result.append(".");
                        result.append(d3);
                        if (qualifier != null) {
                            result.append(".");
                            cleanupModifier(result, qualifier);
                        }
                    } else if (qualifier != null) {
                        result.append(".0.");
                        cleanupModifier(result, qualifier);
                    }
                } else if (qualifier != null) {
                    result.append(".0.0.");
                    cleanupModifier(result, qualifier);
                }
                return result.toString();
            }
        }
        return version;
    }

    static void cleanupModifier(StringBuffer result, String modifier) {
        Matcher m = fuzzyModifier.matcher(modifier);
        if (m.matches())
            modifier = m.group(2);

        for (int i = 0; i < modifier.length(); i++) {
            char c = modifier.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z') || c == '_' || c == '-')
                result.append(c);
        }
    }

    public Set<Feature> getFeaturesContainingBundle(Bundle bundle) throws Exception {
        Set<Feature> features = new HashSet<Feature>();
        for (Map<String, Feature> featureMap : this.getFeatures().values()) {
            for (Feature f : featureMap.values()) {
                for (BundleInfo bi : f.getBundles()) {
                    if (bi.getLocation().equals(bundle.getLocation())) {
                        features.add(f);
                        break;
                    }
                }
            }
        }
        return features;
    }

    private String getFeaturesContainingBundleList(Bundle bundle) throws Exception {
        Set<Feature> features = getFeaturesContainingBundle(bundle);
        StringBuilder buffer = new StringBuilder();
        Iterator<Feature> iter = features.iterator();
        while (iter.hasNext()) {
            Feature feature = iter.next();
            buffer.append(feature.getId());
            if (iter.hasNext()) {
                buffer.append(", ");
            }
        }
        return buffer.toString();
    }

    /**
     * Estimates if the {@link List} of dependencies is satisfied.
     * The method will look into {@link Feature}s that are already installed or now being installed.
     * @param dependencies
     * @param installed
     * @return
     */
    private boolean dependenciesSatisfied(List<Feature> dependencies, Set<Feature> installed) throws Exception {
       boolean satisfied = true;
       for (Feature dep : dependencies) {
           Feature f = getFeature(dep.getName(), dep.getVersion());
           if (f != null && !installed.contains(f)) {
               satisfied = false;
           }
       }
       return satisfied;
    }

}
