package ecology.review.service;

import ecology.review.dto.Requests.ImportBatch;
import ecology.review.dto.Requests.IndividualInput;
import ecology.review.dto.Requests.ObservationInput;
import ecology.review.dto.Requests.SiteInput;
import ecology.review.dto.Requests.SurveyInput;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class DemoSeedRunner implements ApplicationRunner {
  private final ImportService importService;
  private final boolean enabled;

  public DemoSeedRunner(ImportService importService,
                        @Value("${app.demo-seed-enabled:true}") boolean enabled) {
    this.importService = importService;
    this.enabled = enabled;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!enabled) {
      return;
    }
    importService.importBatch(new ImportBatch(
        "demo-reference",
        "demonstration reference data",
        true,
        List.of(
            new SiteInput("RIVER_BEND", "River Bend", 35.6895, 139.6917),
            new SiteInput("NORTH_RIDGE", "North Ridge", 35.9000, 139.9000),
            new SiteInput("SOUTH_MEADOW", "South Meadow", 35.4000, 139.3000),
            new SiteInput("IDLE_BASIN", "Idle Basin", 35.2000, 139.1000)
        ),
        List.of(
            new SurveyInput("S1", "Shift one", "Asia/Tokyo",
                "2026-05-01 08:00", "2026-05-02 08:00",
                List.of("RIVER_BEND", "NORTH_RIDGE", "SOUTH_MEADOW")),
            new SurveyInput("S2", "Shift two", "Asia/Tokyo",
                "2026-05-02 08:00", "2026-05-03 08:00",
                List.of("RIVER_BEND", "NORTH_RIDGE", "SOUTH_MEADOW", "IDLE_BASIN")),
            new SurveyInput("S3", "Unscheduled adjacent interval", "Asia/Tokyo",
                "2026-05-03 08:00", "2026-05-04 08:00", List.of())
        ),
        List.of(
            new IndividualInput("A01", "Alpha"),
            new IndividualInput("B07", "Beta"),
            new IndividualInput("G12", "Gamma")
        ),
        List.of(
            new ObservationInput("OBS-100", "RIVER_BEND", "2026-05-02T07:50:00+09:00", 60,
                "A-01-X", "dark flank hook scar river", .91, "lin", "A01"),
            new ObservationInput("OBS-101", "SOUTH_MEADOW", "2026-05-01T10:20:00+09:00", 60,
                "B077", "pale shoulder notch tail", .86, "lin", "B07"),
            new ObservationInput("OBS-102", "NORTH_RIDGE", "2026-05-02T07:59:59+09:00", 0,
                "G12", "grey crest broad stripe ridge", .82, "mei", "G12")
        )
    ));

    importService.importBatch(new ImportBatch(
        "demo-pending-observations",
        "demonstration ambiguous observations",
        false,
        List.of(),
        List.of(),
        List.of(),
        List.of(
            new ObservationInput("OBS-200", "RIVER_BEND", "2026-05-02T08:00:00+09:00", 30,
                "A01X", "dark flank hook scar blurred", .64, "assistant", null),
            new ObservationInput("OBS-201", "SOUTH_MEADOW", "2026-05-02T08:20:00+09:00", 45,
                "B07", "pale shoulder unclear notch duplicate code", .58, "assistant", null),
            new ObservationInput("OBS-202", "NORTH_RIDGE", "2026-05-02T08:30:00+09:00", 60,
                "A01", "dark flank possible hook", .52, "assistant", null),
            new ObservationInput("OBS-203", "RIVER_BEND", "2026-05-02T09:10:00+09:00", 120,
                "ZZ?", "unknown mottled flank no marker", .31, "visitor", null)
        )
    ));
  }
}
