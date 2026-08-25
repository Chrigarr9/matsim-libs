package org.matsim.contrib.demand_extraction.run;

import java.util.Map;
import java.util.TreeMap;

import org.matsim.contrib.demand_extraction.config.ExMasConfigGroup;

/**
 * Prints the ExMasConfigGroup parameter surface as JSON on stdout.
 *
 * <p>This is the authority for the Python-side schema: {@code getParams()} is the same
 * map {@code RunFingerprint} hashes, so a param that exists here is a param that
 * changes extraction identity. Regenerating the Python snapshot from a source-level
 * regex would miss constant-named annotations and would not know the defaults.
 *
 * <pre>
 * mvn -q -f matsim-libs/pom.xml -pl contribs/drt-demand-extraction compile exec:java \
 *   -Dexec.mainClass="org.matsim.contrib.demand_extraction.run.ExMasParamDump" \
 *   -Denforcer.skip=true
 * </pre>
 */
public final class ExMasParamDump {

	private ExMasParamDump() {}

	public static void main(String[] args) {
		Map<String, String> params = new TreeMap<>(new ExMasConfigGroup().getParams());
		StringBuilder sb = new StringBuilder("{\n");
		int i = 0;
		for (Map.Entry<String, String> e : params.entrySet()) {
			sb.append("  \"").append(escape(e.getKey())).append("\": \"")
					.append(escape(e.getValue() == null ? "" : e.getValue())).append('"');
			sb.append(++i < params.size() ? ",\n" : "\n");
		}
		sb.append("}\n");
		System.out.print(sb);
	}

	private static String escape(String s) {
		return s.replace("\\", "\\\\").replace("\"", "\\\"")
				.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
	}
}
