package org.matsim.contrib.demand_extraction.io.lowmem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.matsim.api.core.v01.Coord;
import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.network.Link;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Leg;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Route;
import org.matsim.contrib.demand_extraction.demand.DrtRequest;
import org.matsim.contrib.drt.routing.DrtRoute;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.config.groups.ScenarioConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;
import org.matsim.core.population.PopulationUtils;
import org.matsim.core.population.routes.RouteUtils;
import org.matsim.core.scoring.functions.ActivityUtilityParameters;
import org.matsim.core.scoring.functions.ScoringParameters;

/**
 * Inverse of {@link PhaseOneDumpWriter}: reads the CSV+BIN+JSON dump back into a
 * fully populated {@code List<DrtRequest>} with reconstructed {@link DrtRequest.ScoringContext}s.
 *
 * <p>The reconstructed {@code ScoringContext.person()} is always {@code null} — Phase 2's
 * scoring path (EqasimScoringAdapter.scoreDrtDirectly + OpportunityCostCalculator) does not
 * consult Person. Walk leg times are recomputed as {@code minDrtAccessEgressDistance /
 * walkSpeed}; the dump persists neither the times nor the speed for walks.
 *
 * <p><b>Stop-based pooling caveat:</b> the dump does not persist
 * {@code DrtRequest.maxWalkDistance}; reloaded requests default to {@code 0.0}. The
 * low-memory two-phase mode therefore only supports door-to-door variants. Enabling
 * stop-based or hyper-pool generation in Phase 2 would silently produce wrong rides —
 * the {@code ExMasConfigGroup.enableStopBased} / {@code enableHyperPooling} flags must be
 * left disabled when this reader is used.
 */
public final class PhaseOneDumpReader {

	public record DumpData(List<DrtRequest> requests, Meta meta) {}

	public record Meta(
			String drtMode,
			double walkSpeed,
			String opportunityCostModel,
			double minDrtAccessEgressDistance,
			String runId,
			int sampleSize,
			int numRequests,
			long phase1WallTimeMs,
			long phase1PeakHeapBytes,
			EqasimScoringParams eqasimScoringParams) {}

	/**
	 * Frozen scalar snapshot of the live eqasim ModeParameters needed by Phase 2's
	 * DRT-only scoring path: alpha + betaTravelTime + betaAccessEgress drive
	 * {@code EqasimScoringAdapter.scoreDrtDirectly}; betaCost + lambda + refDist drive
	 * the distance-specific marginal utility of money used by
	 * {@code BudgetToConstraintsCalculator.budgetToMaxCost}.
	 *
	 * <p>Required when Phase 1 ran with the eqasim adapter; absent otherwise (the
	 * field is {@code null} in {@link Meta} for non-eqasim Phase-1 dumps).
	 */
	public record EqasimScoringParams(
			double drtAlpha_u,
			double drtBetaTravelTime_u_min,
			double drtBetaAccessEgressTime_u_min,
			double betaCost_u_MU,
			double lambdaCostEuclideanDistance,
			double referenceEuclideanDistance_km) {}

	private PhaseOneDumpReader() {}

	public static DumpData read(PhaseOneDumpLayout layout) throws IOException {
		Meta meta = readMeta(layout.metaJson());
		List<DrtRequest> requests = readRequestsCsv(layout.requestsCsv());
		attachScoringContexts(requests, layout.scoringContextsBin(), meta);
		return new DumpData(requests, meta);
	}

	// ---------- meta.json ----------

	private static Meta readMeta(Path metaJson) throws IOException {
		Map<String, String> kv = parseFlatJson(Files.readString(metaJson));
		EqasimScoringParams eqasim = null;
		if (kv.containsKey("eqasim.drtAlpha_u")) {
			eqasim = new EqasimScoringParams(
					Double.parseDouble(requireString(kv, "eqasim.drtAlpha_u")),
					Double.parseDouble(requireString(kv, "eqasim.drtBetaTravelTime_u_min")),
					Double.parseDouble(requireString(kv, "eqasim.drtBetaAccessEgressTime_u_min")),
					Double.parseDouble(requireString(kv, "eqasim.betaCost_u_MU")),
					Double.parseDouble(requireString(kv, "eqasim.lambdaCostEuclideanDistance")),
					Double.parseDouble(requireString(kv, "eqasim.referenceEuclideanDistance_km")));
		}
		return new Meta(
				requireString(kv, "drtMode"),
				Double.parseDouble(requireString(kv, "walkSpeed")),
				requireString(kv, "opportunityCostModel"),
				Double.parseDouble(requireString(kv, "minDrtAccessEgressDistance")),
				requireString(kv, "runId"),
				Integer.parseInt(requireString(kv, "sampleSize")),
				Integer.parseInt(requireString(kv, "numRequests")),
				Long.parseLong(requireString(kv, "phase1WallTimeMs")),
				Long.parseLong(requireString(kv, "phase1PeakHeapBytes")),
				eqasim);
	}

	private static String requireString(Map<String, String> kv, String key) {
		String v = kv.get(key);
		if (v == null) throw new IllegalStateException("phase1_meta.json missing key: " + key);
		return v;
	}

	/**
	 * Parses the flat-JSON dialect produced by {@link PhaseOneDumpWriter}. Each value is
	 * either a quoted string (with backslash escapes) or a bare number; entries are separated
	 * by commas or newlines; outer braces are ignored.
	 */
	static Map<String, String> parseFlatJson(String json) {
		Map<String, String> out = new LinkedHashMap<>();
		int i = 0, n = json.length();
		while (i < n) {
			char c = json.charAt(i);
			if (c == '{' || c == '}' || c == ',' || Character.isWhitespace(c)) {
				i++;
				continue;
			}
			if (c != '"') {
				throw new IllegalStateException("phase1_meta.json: expected '\"' at offset " + i + ", got '" + c + "'");
			}
			StringBuilder key = new StringBuilder();
			i = readJsonString(json, i, key);
			i = skipWhitespace(json, i);
			if (i >= n || json.charAt(i) != ':') {
				throw new IllegalStateException("phase1_meta.json: expected ':' after key '" + key + "'");
			}
			i = skipWhitespace(json, i + 1);
			if (i >= n) {
				throw new IllegalStateException("phase1_meta.json: missing value for key '" + key + "'");
			}
			String value;
			if (json.charAt(i) == '"') {
				StringBuilder sb = new StringBuilder();
				i = readJsonString(json, i, sb);
				value = sb.toString();
			} else {
				int start = i;
				while (i < n && ",}\n\r".indexOf(json.charAt(i)) < 0) i++;
				value = json.substring(start, i).trim();
			}
			out.put(key.toString(), value);
		}
		return out;
	}

	private static int readJsonString(String s, int i, StringBuilder out) {
		// pre: s.charAt(i) == '"'
		i++;
		while (i < s.length()) {
			char c = s.charAt(i++);
			if (c == '"') return i;
			if (c == '\\' && i < s.length()) {
				char esc = s.charAt(i++);
				switch (esc) {
					case '"' -> out.append('"');
					case '\\' -> out.append('\\');
					case '/' -> out.append('/');
					case 'n' -> out.append('\n');
					case 'r' -> out.append('\r');
					case 't' -> out.append('\t');
					case 'u' -> {
						if (i + 4 > s.length()) throw new IllegalStateException("bad \\u escape");
						out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
						i += 4;
					}
					default -> throw new IllegalStateException("bad string escape: \\" + esc);
				}
			} else {
				out.append(c);
			}
		}
		throw new IllegalStateException("unterminated JSON string");
	}

	private static int skipWhitespace(String s, int i) {
		while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
		return i;
	}

	// ---------- drt_requests.csv ----------

	private static List<DrtRequest> readRequestsCsv(Path csv) throws IOException {
		List<String> lines = Files.readAllLines(csv);
		if (lines.isEmpty()) return Collections.emptyList();
		String[] header = lines.get(0).split(",", -1);
		Map<String, Integer> col = new HashMap<>();
		for (int i = 0; i < header.length; i++) col.put(header[i].trim(), i);
		List<DrtRequest> out = new ArrayList<>(lines.size() - 1);
		for (int li = 1; li < lines.size(); li++) {
			String line = lines.get(li);
			if (line.isEmpty()) continue;
			String[] f = line.split(",", -1);
			out.add(buildRequest(f, col));
		}
		return out;
	}

	private static DrtRequest buildRequest(String[] f, Map<String, Integer> col) {
		double directTravelTime = d(f, col, "directTravelTime");
		double maxTravelTime = d(f, col, "maxTravelTime");
		double maxDetourFactor = directTravelTime > 0 ? maxTravelTime / directTravelTime : 0.0;
		return DrtRequest.builder()
				.index(i(f, col, "index"))
				.personId(Id.createPersonId(s(f, col, "personId")))
				.groupId(s(f, col, "groupId"))
				.tripIndex(i(f, col, "tripIndex"))
				.isCommute(b(f, col, "isCommute"))
				.isEducation(b(f, col, "isEducation"))
				.budget(d(f, col, "budget"))
				.bestModeScore(d(f, col, "baseModeScore"))
				.bestMode(nullableString(s(f, col, "baseMode")))
				.originLinkId(Id.createLinkId(s(f, col, "originLinkId")))
				.destinationLinkId(Id.createLinkId(s(f, col, "destinationLinkId")))
				.originX(d(f, col, "originX"))
				.originY(d(f, col, "originY"))
				.destinationX(d(f, col, "destinationX"))
				.destinationY(d(f, col, "destinationY"))
				.originLinkCoordFromX(d(f, col, "originLinkCoordFromX"))
				.originLinkCoordFromY(d(f, col, "originLinkCoordFromY"))
				.originLinkCoordToX(d(f, col, "originLinkCoordToX"))
				.originLinkCoordToY(d(f, col, "originLinkCoordToY"))
				.destinationLinkCoordFromX(d(f, col, "destinationLinkCoordFromX"))
				.destinationLinkCoordFromY(d(f, col, "destinationLinkCoordFromY"))
				.destinationLinkCoordToX(d(f, col, "destinationLinkCoordToX"))
				.destinationLinkCoordToY(d(f, col, "destinationLinkCoordToY"))
				.requestTime(d(f, col, "requestTime"))
				.earliestDeparture(d(f, col, "earliestDeparture"))
				.latestArrival(d(f, col, "latestArrival"))
				.directTravelTime(directTravelTime)
				.directDistance(d(f, col, "directDistance"))
				.maxDetourFactor(maxDetourFactor)
				.originActivityType(nullableString(s(f, col, "originActivityType")))
				.destinationActivityType(nullableString(s(f, col, "destinationActivityType")))
				.carTravelTime(d(f, col, "carTravelTime"))
				.ptTravelTime(d(f, col, "ptTravelTime"))
				.ptAccessibility(d(f, col, "ptAccessibility"))
				.build();
	}

	private static String s(String[] f, Map<String, Integer> col, String key) {
		Integer idx = col.get(key);
		if (idx == null) throw new IllegalStateException("drt_requests CSV missing column: " + key);
		return f[idx];
	}

	private static double d(String[] f, Map<String, Integer> col, String key) {
		String v = s(f, col, key);
		return v.isEmpty() ? 0.0 : Double.parseDouble(v);
	}

	private static int i(String[] f, Map<String, Integer> col, String key) {
		return Integer.parseInt(s(f, col, key));
	}

	private static boolean b(String[] f, Map<String, Integer> col, String key) {
		return Boolean.parseBoolean(s(f, col, key));
	}

	private static String nullableString(String v) {
		return v == null || v.isEmpty() ? null : v;
	}

	// ---------- scoring_contexts.bin → ScoringContext per request ----------

	private static void attachScoringContexts(List<DrtRequest> requests, Path bin, Meta meta) throws IOException {
		Map<Integer, DrtRequest> byIdx = new HashMap<>(requests.size() * 2);
		for (DrtRequest r : requests) byIdx.put(r.index, r);

		try (ScoringContextsBinReader r = new ScoringContextsBinReader(bin)) {
			ScoringContextsBinReader.Header header = r.readHeader();

			Map<String, ActivityUtilityParameters> sharedActParams = buildSharedActivityParams(header.activityTypes());
			String[] typeByIdx = new String[header.activityTypes().size()];
			for (int t = 0; t < typeByIdx.length; t++) {
				typeByIdx[t] = header.activityTypes().get(t).type();
			}

			ScenarioConfigGroup scenarioCfg = ConfigUtils.createConfig().scenario();

			int matched = 0;
			for (int rowIdx = 0; rowIdx < header.numRequests(); rowIdx++) {
				ScoringContextsBinWriter.RequestRow row = r.readRow();
				DrtRequest req = byIdx.get(row.requestIndex());
				if (req == null) {
					throw new IllegalStateException(
							"scoring_contexts.bin row references unknown request index " + row.requestIndex());
				}
				req.setScoringContext(buildContext(req, row, typeByIdx, sharedActParams, scenarioCfg, meta));
				matched++;
			}
			if (matched != requests.size()) {
				throw new IllegalStateException(String.format(
						"scoring_contexts.bin populated %d/%d requests — dump is inconsistent",
						matched, requests.size()));
			}
		}
	}

	/**
	 * Build one {@link ActivityUtilityParameters} per persisted activity type. Shared across
	 * all per-request ScoringParameters so we don't re-allocate the map N times.
	 */
	private static Map<String, ActivityUtilityParameters> buildSharedActivityParams(
			List<ScoringContextsBinWriter.ActivityTypeRow> typeRows) {
		Map<String, ActivityUtilityParameters> out = new HashMap<>(typeRows.size() * 2);
		for (ScoringContextsBinWriter.ActivityTypeRow t : typeRows) {
			ActivityParams ap = new ActivityParams(t.type());
			ap.setTypicalDuration(t.typicalDuration());
			ap.setScoringThisActivityAtAll(t.scoreAtAll());
			out.put(t.type(), new ActivityUtilityParameters.Builder(ap).build());
		}
		return out;
	}

	private static DrtRequest.ScoringContext buildContext(
			DrtRequest req,
			ScoringContextsBinWriter.RequestRow row,
			String[] typeByIdx,
			Map<String, ActivityUtilityParameters> sharedActParams,
			ScenarioConfigGroup scenarioCfg,
			Meta meta) {

		Activity originActivity = restoreRealActivity(row.originActivityTypeIdx(), typeByIdx, req.originLinkId);
		Activity destActivity = restoreRealActivity(row.destActivityTypeIdx(), typeByIdx, req.destinationLinkId);

		ScoringParameters scoringParams = buildScoringParams(
				row.marginalUtilityOfPerforming_s(), row.marginalUtilityOfWaitingPt_s(),
				sharedActParams, scenarioCfg);

		double walkDist = meta.minDrtAccessEgressDistance();
		double walkTime = walkDist / meta.walkSpeed();

		Leg accessLeg = PopulationUtils.createLeg(TransportMode.walk);
		accessLeg.setTravelTime(walkTime);
		Route accessRoute = RouteUtils.createGenericRouteImpl(req.originLinkId, req.originLinkId);
		accessRoute.setDistance(walkDist);
		accessRoute.setTravelTime(walkTime);
		accessLeg.setRoute(accessRoute);

		Leg egressLeg = PopulationUtils.createLeg(TransportMode.walk);
		egressLeg.setTravelTime(walkTime);
		Route egressRoute = RouteUtils.createGenericRouteImpl(req.destinationLinkId, req.destinationLinkId);
		egressRoute.setDistance(walkDist);
		egressRoute.setTravelTime(walkTime);
		egressLeg.setRoute(egressRoute);

		DrtRoute drtRouteTemplate = new DrtRoute(req.originLinkId, req.destinationLinkId);
		drtRouteTemplate.setDirectRideTime(req.directTravelTime);
		drtRouteTemplate.setDistance(req.directDistance);

		Activity synOrigAct = PopulationUtils.createActivityFromLinkId("drt_interaction", req.originLinkId);
		synOrigAct.setCoord(new Coord(req.originX, req.originY));
		synOrigAct.setEndTime(req.requestTime);
		Activity synDestAct = PopulationUtils.createActivityFromLinkId("drt_interaction", req.destinationLinkId);
		synDestAct.setCoord(new Coord(req.destinationX, req.destinationY));

		Person person = null; // Phase-2 invariant
		return new DrtRequest.ScoringContext(
				person, originActivity, destActivity, row.originDuration(), row.destDuration(),
				scoringParams, accessLeg, accessRoute, egressLeg, egressRoute,
				drtRouteTemplate, synOrigAct, synDestAct);
	}

	private static Activity restoreRealActivity(byte typeIdx, String[] typeByIdx, Id<Link> linkId) {
		// idx == -1 means the original activity was synthetic ("unknown" or "drt_interaction").
		// Phase 2 only consults ctx.originActivity().getType() via OpportunityCostCalculator,
		// which falls back to a linear loss when utilParams has no entry for the type — so
		// recreating with "unknown" preserves the original behavior.
		String type = (typeIdx >= 0 && typeIdx < typeByIdx.length) ? typeByIdx[typeIdx] : "unknown";
		return PopulationUtils.createActivityFromLinkId(type, linkId);
	}

	/**
	 * Build a per-request ScoringParameters with the supplied marginal utilities and the
	 * shared {@code utilParams} map. Uses the 4-arg Builder so activity params are referenced,
	 * not copied.
	 */
	private static ScoringParameters buildScoringParams(
			double marginalUtilityOfPerforming_s,
			double marginalUtilityOfWaitingPt_s,
			Map<String, ActivityUtilityParameters> sharedActParams,
			ScenarioConfigGroup scenarioCfg) {

		ScoringConfigGroup scoring = new ScoringConfigGroup();
		scoring.setPerforming_utils_hr(marginalUtilityOfPerforming_s * 3600.0);
		scoring.setMarginalUtlOfWaitingPt_utils_hr(marginalUtilityOfWaitingPt_s * 3600.0);
		// utilParams are passed in directly; the (configGroup, parameterSet, activityParams,
		// scenarioConfig) Builder is the only one that does not re-copy them.
		return new ScoringParameters.Builder(
				scoring, scoring.getScoringParameters(null), sharedActParams, scenarioCfg).build();
	}
}
