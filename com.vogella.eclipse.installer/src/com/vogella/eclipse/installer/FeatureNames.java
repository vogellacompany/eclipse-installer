package com.vogella.eclipse.installer;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.equinox.p2.core.IProvisioningAgent;
import org.eclipse.equinox.p2.core.IProvisioningAgentProvider;
import org.eclipse.equinox.p2.metadata.IInstallableUnit;
import org.eclipse.equinox.p2.query.IQueryResult;
import org.eclipse.equinox.p2.query.IQueryable;
import org.eclipse.equinox.p2.query.QueryUtil;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepository;
import org.eclipse.equinox.p2.repository.metadata.IMetadataRepositoryManager;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;

/** Reads feature names from the update sites. */
public final class FeatureNames {

	private FeatureNames() {
	}

	/** Maps ids missing from the update sites to {@code null}. */
	public static Map<String, String> load(List<URI> repositories, List<String> ids) throws IOException {
		BundleContext context = FrameworkUtil.getBundle(FeatureNames.class).getBundleContext();
		ServiceReference<IProvisioningAgentProvider> ref = context.getServiceReference(IProvisioningAgentProvider.class);
		if (ref == null) {
			return Map.of();
		}
		Path dataArea = Files.createTempDirectory("eclipse-installer-names");
		IProvisioningAgent agent = null;
		ExecutorService executor = Executors.newFixedThreadPool(Math.max(1, repositories.size()));
		try {
			agent = context.getService(ref).createAgent(dataArea.toUri());
			IMetadataRepositoryManager manager = agent.getService(IMetadataRepositoryManager.class);
			List<Future<IMetadataRepository>> futures = new ArrayList<>();
			for (URI uri : repositories) {
				futures.add(executor.submit(() -> manager.loadRepository(uri, new NullProgressMonitor())));
			}
			List<IMetadataRepository> loaded = new ArrayList<>();
			for (Future<IMetadataRepository> future : futures) {
				try {
					loaded.add(future.get());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return Map.of();
				} catch (Exception e) {
					// Reported by the installation itself.
				}
			}
			IQueryable<IInstallableUnit> all = QueryUtil.compoundQueryable(loaded);
			Map<String, String> names = new HashMap<>();
			for (String id : ids) {
				IQueryResult<IInstallableUnit> result = all
						.query(QueryUtil.createLatestQuery(QueryUtil.createIUQuery(id)), null);
				names.put(id, result.isEmpty() ? null : name(result.iterator().next()));
			}
			return names;
		} catch (Exception e) {
			return Map.of();
		} finally {
			executor.shutdownNow();
			if (agent != null) {
				agent.stop();
			}
			context.ungetService(ref);
			Archives.deleteRecursively(dataArea);
		}
	}

	public static String name(IInstallableUnit iu) {
		String name = iu.getProperty(IInstallableUnit.PROP_NAME, Locale.getDefault().toString());
		return name == null || name.isBlank() ? iu.getId() : name;
	}
}
