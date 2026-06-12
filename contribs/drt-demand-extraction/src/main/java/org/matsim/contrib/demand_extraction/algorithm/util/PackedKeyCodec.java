package org.matsim.contrib.demand_extraction.algorithm.util;

/**
 * Packs (originLinkIndex, destLinkIndex, timeBin) into one primitive long cache key,
 * and (originLinkIndex, timeBin) into one SSSP-mark key.
 *
 * <p>Segment-key layout: origin 24 bits | dest 24 bits | bin 16 bits. Guards fail fast
 * if a network or bin count outgrows the layout (16.7M links, 65k bins). SSSP-key
 * layout: origin 24 bits | bin 16 bits (40 bits total, same guards).
 *
 * <p>Link indices are MATSim {@code Id.index()} values — stable within one JVM run,
 * NOT across JVMs. Anything persisted (connection-cache journal, CSV exports) must
 * keep using link-id strings; this codec is for in-memory structures only.
 *
 * <p>Shared foundation: introduced for the connection-cache tiers
 * (2026-06-12-connection-cache-memory-design §3.1) and reused by the structural
 * cleanup's typed export keys.
 */
public final class PackedKeyCodec {

	public static final int MAX_LINK_INDEX = (1 << 24) - 1;
	public static final int MAX_TIME_BIN = (1 << 16) - 1;

	private PackedKeyCodec() {
		// static utility
	}

	/** Pack an OD/bin triple into one long: origin 24b | dest 24b | bin 16b. */
	public static long segmentKey(int originLinkIdx, int destLinkIdx, int timeBin) {
		checkLink(originLinkIdx);
		checkLink(destLinkIdx);
		checkBin(timeBin);
		return ((long) originLinkIdx << 40) | ((long) destLinkIdx << 16) | timeBin;
	}

	public static int origin(long segmentKey) {
		return (int) (segmentKey >>> 40);
	}

	public static int dest(long segmentKey) {
		return (int) ((segmentKey >>> 16) & 0xFFFFFF);
	}

	public static int bin(long segmentKey) {
		return (int) (segmentKey & 0xFFFF);
	}

	/** Pack an SSSP-completion mark into one long: origin 24b | bin 16b. */
	public static long ssspKey(int originLinkIdx, int timeBin) {
		checkLink(originLinkIdx);
		checkBin(timeBin);
		return ((long) originLinkIdx << 16) | timeBin;
	}

	public static int ssspOrigin(long ssspKey) {
		return (int) (ssspKey >>> 16);
	}

	public static int ssspBin(long ssspKey) {
		return (int) (ssspKey & 0xFFFF);
	}

	private static void checkLink(int idx) {
		if (idx < 0 || idx > MAX_LINK_INDEX) {
			throw new IllegalArgumentException("link index out of packed range [0, "
					+ MAX_LINK_INDEX + "]: " + idx);
		}
	}

	private static void checkBin(int bin) {
		if (bin < 0 || bin > MAX_TIME_BIN) {
			throw new IllegalArgumentException("time bin out of packed range [0, "
					+ MAX_TIME_BIN + "]: " + bin);
		}
	}
}
