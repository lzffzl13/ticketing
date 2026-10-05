import com.ticketing.inventory.InventoryModels.SeatStatus;
import com.ticketing.inventory.InventoryModels.SessionSeat;
import com.ticketing.inventory.SeatRecommendationEngine;
import com.ticketing.inventory.SeatRecommendationModels.Result;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/** Run with Java 21 source-file mode and target/classes on the classpath; no database or HTTP. */
public final class SeatRecommendationBenchmark {
    private static volatile long consumedResult;
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static final Instant FIXED_TIME = Instant.parse("2026-09-28T00:00:00Z");

    public static void main(String[] arguments) throws Exception {
        var options = options(arguments);
        int[] sizes = Arrays.stream(options.get("sizes").split(",")).mapToInt(Integer::parseInt).toArray();
        int areas = positive(options, "areas");
        int seatsPerRow = positive(options, "seats-per-row");
        int segments = positive(options, "segments");
        int tiers = positive(options, "tiers");
        int quantity = positive(options, "quantity");
        int limit = positive(options, "limit");
        int warmup = positive(options, "warmup");
        int samples = positive(options, "samples");
        long seed = Long.parseLong(options.get("seed"));
        double unavailableRatio = Double.parseDouble(options.get("unavailable-ratio"));
        if (!Double.isFinite(unavailableRatio) || unavailableRatio < 0 || unavailableRatio >= 1) {
            throw new IllegalArgumentException("unavailable-ratio must be in [0,1)");
        }
        if (segments > seatsPerRow || seatsPerRow % segments != 0 || limit > 20) {
            throw new IllegalArgumentException("Segments must divide seats-per-row; limit must be at most 20");
        }
        Path prefix = Path.of(options.get("output-prefix")).toAbsolutePath();
        Files.createDirectories(prefix.getParent());
        var summary = new StringBuilder("seatCount,areaCount,rowsPerArea,seatsPerRow,segmentsPerRow,tierCount,unavailableRatio,seed,effectiveSeed,layoutSha256,availableSeatCount,quantity,limit,forbidNewOrphan,threads,warmup,samples,validCandidateCount,returnedCandidateCount,meanMicroseconds,p50Microseconds,p95Microseconds,minMicroseconds,maxMicroseconds\n");
        var raw = new StringBuilder("seatCount,sample,elapsedNanoseconds\n");
        var engine = new SeatRecommendationEngine();
        Set<Long> enabledTierIds = java.util.stream.LongStream.rangeClosed(1, tiers).boxed().collect(Collectors.toSet());

        for (int size : sizes) {
            if (size <= 0 || size % (areas * seatsPerRow) != 0) {
                throw new IllegalArgumentException("Each size must divide evenly into areas * seats-per-row");
            }
            int rowsPerArea = size / (areas * seatsPerRow);
            long effectiveSeed = seed + size;
            List<SessionSeat> layout = layout(areas, rowsPerArea, seatsPerRow, segments, tiers, unavailableRatio, effectiveSeed);
            String layoutHash = layoutHash(layout);
            for (int iteration = 0; iteration < warmup; iteration++) {
                consumedResult = consume(engine.recommend(layout, enabledTierIds, null, null, quantity, limit, true));
            }
            long[] elapsed = new long[samples];
            Result last = null;
            for (int sample = 0; sample < samples; sample++) {
                long start = System.nanoTime();
                var result = engine.recommend(layout, enabledTierIds, null, null, quantity, limit, true);
                elapsed[sample] = System.nanoTime() - start;
                // Consume results outside the measured interval; generation, I/O and source compilation are excluded.
                consumedResult = consume(result);
                last = result;
                raw.append(size).append(',').append(sample + 1).append(',').append(elapsed[sample]).append('\n');
            }
            long[] sorted = elapsed.clone();
            Arrays.sort(sorted);
            double mean = Arrays.stream(elapsed).average().orElseThrow() / 1_000.0;
            long available = layout.stream().filter(seat -> seat.seatStatus() == SeatStatus.AVAILABLE).count();
            summary.append(size).append(',').append(areas).append(',').append(rowsPerArea).append(',')
                    .append(seatsPerRow).append(',').append(segments).append(',').append(tiers).append(',')
                    .append(unavailableRatio).append(',').append(seed).append(',').append(effectiveSeed).append(',')
                    .append(layoutHash).append(',').append(available).append(',').append(quantity).append(',')
                    .append(limit).append(",true,1,").append(warmup).append(',').append(samples).append(',')
                    .append(last.diagnostics().validCandidateCount()).append(',').append(last.candidates().size()).append(',')
                    .append(decimal(mean)).append(',').append(decimal(percentile(sorted, 0.50) / 1_000.0)).append(',')
                    .append(decimal(percentile(sorted, 0.95) / 1_000.0)).append(',')
                    .append(decimal(sorted[0] / 1_000.0)).append(',').append(decimal(sorted[sorted.length - 1] / 1_000.0)).append('\n');
            System.out.printf(java.util.Locale.ROOT,
                    "seats=%d; areas=%d; rows/area=%d; available=%d; validCandidates=%d; mean=%.3f us; p95=%.3f us%n",
                    size, areas, rowsPerArea, available, last.diagnostics().validCandidateCount(), mean, percentile(sorted, 0.95) / 1_000.0);
        }
        Path summaryPath = Path.of(prefix + ".summary.csv");
        Path samplesPath = Path.of(prefix + ".samples.csv");
        Files.writeString(summaryPath, summary, StandardCharsets.UTF_8);
        Files.writeString(samplesPath, raw, StandardCharsets.UTF_8);
        System.out.println("Summary=" + summaryPath);
        System.out.println("Samples=" + samplesPath);
        System.out.println("Measured: complete current realtime recommendation baseline, one thread, in-memory inputs; no HTTP or database.");
    }

    private static Map<String, String> options(String[] arguments) {
        Map<String, String> defaults = Map.ofEntries(
                Map.entry("sizes", "1000,5000,10000"), Map.entry("areas", "2"),
                Map.entry("seats-per-row", "20"), Map.entry("segments", "2"), Map.entry("tiers", "3"),
                Map.entry("unavailable-ratio", "0.05"), Map.entry("seed", "20260928"),
                Map.entry("quantity", "2"), Map.entry("limit", "5"),
                Map.entry("warmup", "50"), Map.entry("samples", "100"),
                Map.entry("output-prefix", ".local/seat-recommendations"));
        var options = new HashMap<>(defaults);
        if (arguments.length % 2 != 0) { throw new IllegalArgumentException("Use --name value option pairs"); }
        for (int index = 0; index < arguments.length; index += 2) {
            String name = arguments[index].startsWith("--") ? arguments[index].substring(2) : "";
            if (!defaults.containsKey(name)) { throw new IllegalArgumentException("Unknown option: " + arguments[index]); }
            options.put(name, arguments[index + 1]);
        }
        return options;
    }

    private static int positive(Map<String, String> options, String name) {
        int value = Integer.parseInt(options.get(name));
        if (value <= 0) { throw new IllegalArgumentException(name + " must be positive"); }
        return value;
    }

    private static List<SessionSeat> layout(int areas, int rowsPerArea, int seatsPerRow, int segments,
                                         int tiers, double unavailableRatio, long effectiveSeed) {
        var seats = new ArrayList<SessionSeat>(areas * rowsPerArea * seatsPerRow);
        var random = new Random(effectiveSeed);
        long identity = 0;
        for (int area = 0; area < areas; area++) {
            String areaCode = "AREA-" + (area + 1);
            for (int row = 0; row < rowsPerArea; row++) {
                long tierId = 1 + (area * rowsPerArea + row) % tiers;
                String rowLabel = "R" + (row + 1);
                for (int number = 1; number <= seatsPerRow; number++) {
                    identity++;
                    int segment = (number - 1) / (seatsPerRow / segments);
                    var x = BigDecimal.valueOf(number).subtract(BigDecimal.valueOf(seatsPerRow + 1).divide(TWO))
                            .add(BigDecimal.valueOf(segment).subtract(BigDecimal.valueOf(segments - 1).divide(TWO))
                                    .multiply(new BigDecimal("1.5")));
                    var status = random.nextDouble() < unavailableRatio ? SeatStatus.UNAVAILABLE : SeatStatus.AVAILABLE;
                    seats.add(new SessionSeat(identity, 1L, identity, (long)area + 1, areaCode, "Generated " + areaCode,
                            area, rowLabel, row, number, x, BigDecimal.valueOf(row), "SEG-" + segment,
                            tierId, "Tier " + tierId, BigDecimal.valueOf(100 + tierId * 10), status, FIXED_TIME, FIXED_TIME));
                }
            }
        }
        // Fixed shuffle makes the benchmark include deterministic sorting work on unordered input.
        Collections.shuffle(seats, new Random(effectiveSeed ^ 0x5DEECE66DL));
        return List.copyOf(seats);
    }

    private static long consume(Result result) {
        long value = result.diagnostics().validCandidateCount();
        for (var candidate : result.candidates()) {
            value = value * 31 + candidate.totalPrice().hashCode();
            value = value * 31 + candidate.centerDistanceSquared().hashCode();
            for (var seat : candidate.seats()) { value = value * 31 + seat.id(); }
        }
        return value;
    }

    private static long percentile(long[] sorted, double quantile) {
        return sorted[Math.max(0, (int)Math.ceil(sorted.length * quantile) - 1)];
    }

    private static String decimal(double value) { return String.format(java.util.Locale.ROOT, "%.3f", value); }

    private static String layoutHash(List<SessionSeat> layout) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        for (var seat : layout) {
            String line = seat.id() + "|" + seat.areaCodeSnapshot() + "|" + seat.rowLabelSnapshot() + "|"
                    + seat.seatNumberSnapshot() + "|" + seat.aisleSegmentSnapshot() + "|" + seat.priceTierId() + "|"
                    + seat.coordinateXSnapshot().toPlainString() + "|" + seat.coordinateYSnapshot().toPlainString() + "|"
                    + seat.seatStatus() + "\n";
            digest.update(line.getBytes(StandardCharsets.UTF_8));
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
