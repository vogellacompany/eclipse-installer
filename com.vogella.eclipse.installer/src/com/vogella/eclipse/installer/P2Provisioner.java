package com.vogella.eclipse.installer;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.internal.p2.director.app.DirectorApplication;
import org.eclipse.equinox.internal.provisional.p2.director.PlanExecutionHelper;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.IProvisioningAgentProvider;
import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.equinox.p2.core.UIServices;
import org.eclipse.equinox.p2.engine.IEngine;
import org.eclipse.equinox.p2.engine.IProfile;
import org.eclipse.equinox.p2.engine.IProfileRegistry;
import org.eclipse.equinox.p2.engine.IProvisioningPlan;
import org.eclipse.equinox.p2.engine.ProvisioningContext;
import org.eclipse.equinox.p2.engine.query.UserVisibleRootQuery;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.metadata.IRequirement;
import org.eclipse.equinox.p2.metadata.MetadataFactory;
import org.eclipse.equinox.p2.metadata.Version;
import org.eclipse.equinox.p2.metadata.VersionRange;
import org.eclipse.equinox.p2.planner.IPlanner;
import org.eclipse.equinox.p2.planner.IProfileChangeRequest;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

import com.vogella.eclipse.installer.Settings.Feature;

/** Installs or updates features in another installation's p2 profile. */
final class P2Provisioner {

	private static final String PROP_P2_PROFILE = "eclipse.p2.profile";

	private final Path target;
	private final StatusListener listener;
	private IProvisioningAgent agent;
	private IPlanner planner;
	private IEngine engine;

	P2Provisioner(Path target, StatusListener listener) {
		this.target = target;
		this.listener = listener;
	}

	List<String> installOrUpdate(List<URI> repositories, List<Feature> features) throws CoreException {
		BundleContext context = FrameworkUtil.getBundle(P2Provisioner.class).getBundleContext();
		ServiceReference<IProvisioningAgentProvider> ref = context.getServiceReference(IProvisioningAgentProvider.class);
		if (ref == null) {
			throw new CoreException(Status.error("The p2 agent provider is not available"));
		}
		IProvisioningAgentProvider provider = context.getService(ref);
		try {
			agent = provider.createAgent(target.resolve("p2").toUri());
			agent.registerService(IProvisioningAgent.INSTALLER_AGENT, provider.createAgent(null));
			return run(repositories, features);
		} catch (ProvisionException e) {
			throw new CoreException(Status.error(describe(e.getStatus()), e));
		} finally {
			if (agent != null) {
				agent.stop();
			}
			context.ungetService(ref);
		}
	}

	private List<String> run(List<URI> repositories, List<Feature> features) throws CoreException {
		listener.step("Reading the installation");
		String profileId = profileId();
		agent.registerService(PROP_P2_PROFILE, profileId);
		agent.registerService(UIServices.SERVICE_NAME, new DirectorApplication.AvoidTrustPromptService());
		IProfileRegistry registry = agent.getService(IProfileRegistry.class);
		planner = agent.getService(IPlanner.class);
		engine = agent.getService(IEngine.class);
		IProfile profile = registry.getProfile(profileId);
		if (profile == null) {
			throw new CoreException(Status.error("No p2 profile " + profileId + " found in " + target.resolve("p2")));
		}
		// All installed units count, including features that came in through the product or another feature.
		Map<String, IInstallableUnit> installed = new HashMap<>();
		for (IInstallableUnit iu : profile.query(QueryUtil.createIUAnyQuery(), null)) {
			installed.merge(iu.getId(), iu, (a, b) -> a.getVersion().compareTo(b.getVersion()) >= 0 ? a : b);
		}
		Set<String> roots = new HashSet<>();
		for (IInstallableUnit iu : profile.query(new UserVisibleRootQuery(), null)) {
			roots.add(iu.getId());
		}

		listener.step("Loading update sites");
		IQueryable<IInstallableUnit> available = loadRepositories(repositories);
		Map<String, IInstallableUnit> latest = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();
		for (Feature feature : features) {
			IQueryResult<IInstallableUnit> result = available
					.query(QueryUtil.createLatestQuery(QueryUtil.createIUQuery(feature.id())), null);
			if (result.isEmpty()) {
				missing.add(feature.id());
			} else {
				latest.put(feature.id(), result.iterator().next());
			}
		}
		if (!missing.isEmpty()) {
			throw new CoreException(Status.error("Not found in the update sites: " + String.join(", ", missing)));
		}

		List<String> changed = new ArrayList<>();
		List<IInstallableUnit> additions = new ArrayList<>();
		List<IInstallableUnit> removals = new ArrayList<>();
		List<IRequirement> floors = new ArrayList<>();
		for (Feature feature : features) {
			IInstallableUnit newest = latest.get(feature.id());
			IInstallableUnit old = installed.get(feature.id());
			if (old != null) {
				// Keeps the plan from downgrading the feature to satisfy another feature's dependencies.
				floors.add(MetadataFactory.createRequirement(IInstallableUnit.NAMESPACE_IU_ID, feature.id(),
						new VersionRange(old.getVersion(), true, Version.MAX_VERSION, true), null, false, false));
			}
			String name = feature.label() != null ? feature.label() : FeatureNames.name(newest);
			if (old != null && old.getVersion().equals(newest.getVersion())) {
				listener.log(name + " " + newest.getVersion() + " is up to date");
				continue;
			}
			if (old != null && old.getVersion().compareTo(newest.getVersion()) > 0) {
				listener.log(name + " " + old.getVersion() + " is kept, the update sites only offer " + newest.getVersion());
				continue;
			}
			listener.log(old == null ? name + ": install " + newest.getVersion()
					: name + ": update " + old.getVersion() + " to " + newest.getVersion());
			additions.add(newest);
			// p2 drops an IU that is both added and removed; a unit other features require stays installed.
			if (old != null && roots.contains(feature.id())) {
				removals.add(old);
			}
			changed.add(feature.id());
		}
		if (changed.isEmpty()) {
			return changed;
		}

		boolean wasRoaming = Boolean.parseBoolean(profile.getProperty(IProfile.PROP_ROAMING));
		Exception failure = null;
		try {
			profile = pinLocations(profile);
			listener.step("Resolving dependencies");
			ProvisioningContext provisioningContext = new ProvisioningContext(agent);
			URI[] uris = repositories.toArray(URI[]::new);
			provisioningContext.setMetadataRepositories(uris);
			provisioningContext.setArtifactRepositories(uris);
			IProfileChangeRequest request = planner.createChangeRequest(profile);
			for (IInstallableUnit iu : additions) {
				request.add(iu);
				request.setInstallableUnitProfileProperty(iu, IProfile.PROP_PROFILE_ROOT_IU, Boolean.TRUE.toString());
			}
			request.removeAll(removals);
			request.addExtraRequirements(floors);
			IProvisioningPlan plan = planner.getProvisioningPlan(request, provisioningContext, monitor());
			check(plan.getStatus());

			listener.step("Downloading and installing");
			// Also runs the installer plan that adds provisioning extensions some features need.
			check(PlanExecutionHelper.executePlan(plan, engine, provisioningContext, monitor()));
		} catch (CoreException | RuntimeException e) {
			failure = e;
			throw e;
		} finally {
			if (wasRoaming) {
				try {
					setRoaming(registry.getProfile(profileId));
				} catch (CoreException e) {
					if (failure == null) {
						throw e;
					}
					failure.addSuppressed(e);
				}
			}
		}
		return changed;
	}

	private IQueryable<IInstallableUnit> loadRepositories(List<URI> repositories) throws CoreException {
		IMetadataRepositoryManager manager = agent.getService(IMetadataRepositoryManager.class);
		List<IMetadataRepository> loaded = new ArrayList<>();
		for (URI uri : repositories) {
			listener.log("Loading " + uri);
			try {
				loaded.add(manager.loadRepository(uri, monitor()));
			} catch (ProvisionException e) {
				throw new CoreException(Status.error("Cannot load " + uri + ": " + describe(e.getStatus()), e));
			}
		}
		return QueryUtil.compoundQueryable(loaded);
	}

	private String profileId() throws CoreException {
		Path configIni = target.resolve("configuration/config.ini");
		if (Files.isRegularFile(configIni)) {
			Properties props = new Properties();
			try (InputStream in = Files.newInputStream(configIni)) {
				props.load(in);
			} catch (IOException e) {
				throw new CoreException(Status.error("Cannot read " + configIni + ": " + e.getMessage(), e));
			}
			String id = props.getProperty(PROP_P2_PROFILE);
			if (id != null) {
				return id;
			}
		}
		IProfile[] profiles = agent.getService(IProfileRegistry.class).getProfiles();
		if (profiles.length == 0) {
			throw new CoreException(Status.error("No p2 profile found in " + target.resolve("p2")));
		}
		return profiles[0].getProfileId();
	}

	/** Points a roaming profile at the target, as the p2 director does. */
	private IProfile pinLocations(IProfile profile) throws CoreException {
		if (!Boolean.parseBoolean(profile.getProperty(IProfile.PROP_ROAMING))) {
			return profile;
		}
		String folder = target.toString();
		if (folder.equals(profile.getProperty(IProfile.PROP_INSTALL_FOLDER))
				&& folder.equals(profile.getProperty(IProfile.PROP_CACHE))) {
			return profile;
		}
		IProfileChangeRequest request = planner.createChangeRequest(profile);
		request.setProfileProperty(IProfile.PROP_INSTALL_FOLDER, folder);
		request.setProfileProperty(IProfile.PROP_CACHE, folder);
		request.setProfileProperty(IProfile.PROP_ROAMING, Boolean.FALSE.toString());
		executeWithoutRepositories(request);
		return agent.getService(IProfileRegistry.class).getProfile(profile.getProfileId());
	}

	private void setRoaming(IProfile profile) throws CoreException {
		if (profile == null || Boolean.parseBoolean(profile.getProperty(IProfile.PROP_ROAMING))) {
			return;
		}
		IProfileChangeRequest request = planner.createChangeRequest(profile);
		request.setProfileProperty(IProfile.PROP_ROAMING, Boolean.TRUE.toString());
		executeWithoutRepositories(request);
	}

	private void executeWithoutRepositories(IProfileChangeRequest request) throws CoreException {
		ProvisioningContext context = new ProvisioningContext(agent);
		context.setMetadataRepositories();
		context.setArtifactRepositories();
		// Also runs to restore the profile after Cancel, so only errors stop it.
		IProvisioningPlan plan = planner.getProvisioningPlan(request, context, new NullProgressMonitor());
		checkErrors(plan.getStatus());
		checkErrors(engine.perform(plan, new NullProgressMonitor()));
	}

	private void check(IStatus status) throws CoreException {
		if (status.getSeverity() == IStatus.CANCEL || listener.isCanceled()) {
			throw new OperationCanceledException();
		}
		checkErrors(status);
	}

	private static void checkErrors(IStatus status) throws CoreException {
		if (status.matches(IStatus.ERROR)) {
			throw new CoreException(Status.error(describe(status), new CoreException(status)));
		}
	}

	static String describe(IStatus status) {
		StringBuilder sb = new StringBuilder(status.getMessage());
		append(sb, status, 1);
		return sb.toString();
	}

	private static void append(StringBuilder sb, IStatus status, int depth) {
		for (IStatus child : status.getChildren()) {
			if (child.matches(IStatus.ERROR | IStatus.WARNING)) {
				sb.append(System.lineSeparator()).append("  ".repeat(depth)).append(child.getMessage());
				append(sb, child, depth + 1);
			}
		}
		if (status.getException() != null && status.getChildren().length == 0
				&& status.getException().getMessage() != null
				&& !status.getMessage().contains(status.getException().getMessage())) {
			sb.append(System.lineSeparator()).append("  ".repeat(depth)).append(status.getException().getMessage());
		}
	}

	/** Only passes on cancellation. */
	private IProgressMonitor monitor() {
		return new NullProgressMonitor() {
			@Override
			public boolean isCanceled() {
				return listener.isCanceled();
			}
		};
	}
}
