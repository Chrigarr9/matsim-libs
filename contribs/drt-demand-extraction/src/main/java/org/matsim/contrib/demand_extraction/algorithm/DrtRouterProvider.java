package org.matsim.contrib.demand_extraction.algorithm;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.network.Network;
import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;
import org.matsim.core.network.NetworkUtils;
import org.matsim.core.router.costcalculators.TravelDisutilityFactory;
import org.matsim.core.router.util.LeastCostPathCalculator;
import org.matsim.core.router.util.LeastCostPathCalculatorFactory;
import org.matsim.core.router.util.TravelDisutility;
import org.matsim.core.router.util.TravelTime;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;

/**
 * Builds the DRT-specific {@link LeastCostPathCalculator}, optionally filtering the network
 * to {@code drtAllowedModes}. Promoted from an inner class of {@code ExMasAlgorithmModule}
 * so the low-memory Phase-2 module can reuse the exact same routing setup — bit-equality of
 * Phase-2 vs single-process per-ride distances/times is a hard gate of the two-phase mode.
 *
 * <p>Pre-indexes link/node IDs in sorted order so SpeedyALT's lazy {@code Id.index()}
 * assignment doesn't introduce per-run drift across HashMap iteration orders.
 */
public class DrtRouterProvider implements Provider<LeastCostPathCalculator> {
	@Inject
	private Network network;
	@Inject
	@Named(TransportMode.car)
	private TravelDisutilityFactory travelDisutilityFactory;
	@Inject
	@Named(TransportMode.car)
	private TravelTime travelTime;
	@Inject
	private LeastCostPathCalculatorFactory factory;
	@Inject
	private ExMasConfigGroup exmasConfig;

	private Network cachedFilteredNetwork;
	private Network preIndexedNetwork;

	@Override
	public LeastCostPathCalculator get() {
		Network routingNetwork = getOrCreateRoutingNetwork();
		TravelDisutility travelDisutility = travelDisutilityFactory.createTravelDisutility(travelTime);
		return factory.createPathCalculator(routingNetwork, travelDisutility, travelTime);
	}

	private synchronized Network getOrCreateRoutingNetwork() {
		Set<String> allowedModes = exmasConfig.getDrtAllowedModes();

		Network routingNetwork;
		if (allowedModes == null || allowedModes.isEmpty()) {
			routingNetwork = network;
		} else {
			if (cachedFilteredNetwork == null) {
				cachedFilteredNetwork = buildFilteredNetworkDeterministic(network, allowedModes);
			}
			routingNetwork = cachedFilteredNetwork;
		}

		ensureNetworkIdsPreIndexedDeterministic(routingNetwork);
		return routingNetwork;
	}

	private void ensureNetworkIdsPreIndexedDeterministic(Network routingNetwork) {
		if (preIndexedNetwork == routingNetwork) {
			return;
		}
		List<org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Node>> nodeIds =
				routingNetwork.getNodes().keySet().stream().sorted().toList();
		for (org.matsim.api.core.v01.Id<org.matsim.api.core.v01.network.Node> nodeId : nodeIds) {
			nodeId.index();
		}
		List<org.matsim.api.core.v01.Id<Link>> linkIds =
				routingNetwork.getLinks().keySet().stream().sorted().toList();
		for (org.matsim.api.core.v01.Id<Link> linkId : linkIds) {
			linkId.index();
		}
		preIndexedNetwork = routingNetwork;
	}

	private Network buildFilteredNetworkDeterministic(Network originalNetwork, Set<String> allowedModes) {
		Network filteredNetwork = NetworkUtils.createNetwork();
		List<Link> allowedLinks = originalNetwork.getLinks().values().stream()
				.filter(Objects::nonNull)
				.filter(link -> isLinkAllowed(link, allowedModes))
				.sorted(Comparator.comparing(link -> link.getId().toString()))
				.collect(Collectors.toList());
		for (Link link : allowedLinks) {
			addLinkWithNodes(filteredNetwork, link);
		}
		return filteredNetwork;
	}

	private boolean isLinkAllowed(Link link, Set<String> allowedModes) {
		Set<String> linkModes = link.getAllowedModes();
		for (String mode : allowedModes) {
			if (linkModes.contains(mode)) {
				return true;
			}
		}
		return false;
	}

	private void addLinkWithNodes(Network network, Link link) {
		if (!network.getNodes().containsKey(link.getFromNode().getId())) {
			network.addNode(link.getFromNode());
		}
		if (!network.getNodes().containsKey(link.getToNode().getId())) {
			network.addNode(link.getToNode());
		}
		network.addLink(link);
	}
}
