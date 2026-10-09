/*****************************************
  *----------------------------------
  |  ThisScriptVersion: 1.0.0     |
  |  © 2026 By ISAMI ABE          |
  |  LastUpdate: 2026-10-06       |
  |  License: MIT License         |
  |  server_status (サーバー状態API) |
----------------------------------*
******************************************/
import com.sun.management.OperatingSystemMXBean;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.DecimalFormat;

public class ServerStatus {
  private static final DecimalFormat DF = new DecimalFormat("#.##");

  public static void main(String[] args) {
    // ---- 電源 (sysfsが無い環境ではACとみなす。Docker等で落とさないためのデフォルト) ----
    String acStatus = readOrDefault("/sys/class/power_supply/AC0/online", "1").trim();
    String power = acStatus.equals("1") ? "AC" : "Battery";

    // ---- バッテリー (AC中は無意味な情報なのでnull。実際にバッテリー稼働中のみ埋める。
    //      読み取れない値も勝手に既定値をでっち上げず null にする) ----
    Integer battery = null;
    String status = null;
    Double hours = null;

    if (!power.equals("AC")) {
      String statusRaw = readIfExists("/sys/class/power_supply/BAT0/status");
      String capacityRaw = readIfExists("/sys/class/power_supply/BAT0/capacity");
      status = statusRaw == null ? null : statusRaw.trim();

      if (capacityRaw != null) {
        try {
          battery = Integer.parseInt(capacityRaw.trim());
        } catch (NumberFormatException e) {
          battery = null;
        }
      }

      if ("Discharging".equals(status)) {
        String energyNow = readIfExists("/sys/class/power_supply/BAT0/energy_now");
        String powerNow = readIfExists("/sys/class/power_supply/BAT0/power_now");
        if (energyNow != null && powerNow != null) {
          try {
            double e = Double.parseDouble(energyNow);
            double p = Double.parseDouble(powerNow);
            if (p > 0) hours = e / p;
          } catch (NumberFormatException ex) {
            hours = null;
          }
        }
      }
    }

    // ---- CPU / メモリ / ディスク (取得できない項目は null にしてプロセスは落とさない) ----
    Double cpuPercent = readCpuPercent();
    double[] mem = readMemoryGb();
    double[] disk = readDiskGb("/");

    StringBuilder json = new StringBuilder();
    json.append("{\n");
    json.append("  \"power\": \"").append(power).append("\",\n");
    json.append("  \"battery\": ").append(battery == null ? "null" : battery).append(",\n");
    json.append("  \"status\": ").append(status == null ? "null" : "\"" + status + "\"").append(",\n");
    appendField(json, "remaining_hours", hours);
    appendField(json, "cpu_percent", cpuPercent);
    appendField(json, "memory_percent", mem == null || mem[1] <= 0 ? null : (mem[0] / mem[1]) * 100.0);
    appendField(json, "memory_used_gb", mem == null ? null : mem[0]);
    appendField(json, "memory_total_gb", mem == null ? null : mem[1]);
    appendField(json, "disk_percent", disk == null || disk[1] <= 0 ? null : (disk[0] / disk[1]) * 100.0);
    appendField(json, "disk_used_gb", disk == null ? null : disk[0]);
    json.append("  \"disk_total_gb\": ").append(numOrNull(disk == null ? null : disk[1])).append("\n");
    json.append("}");

    System.out.println(json);
  }

  private static void appendField(StringBuilder json, String key, Double value) {
    json.append("  \"").append(key).append("\": ").append(numOrNull(value)).append(",\n");
  }

  private static String numOrNull(Double v) {
    return v == null ? "null" : DF.format(v);
  }

  // CGIは毎回新しいJVMを起動するため、getCpuLoad()は前回呼び出しからの差分を取れず
  // 常に0付近を返してしまう。/proc/statを間隔を空けて2回読み、jiffiesの差分から算出する。
  private static Double readCpuPercent() {
    try {
      long[] first = readProcStatTotals();
      if (first == null) return null;
      Thread.sleep(200);
      long[] second = readProcStatTotals();
      if (second == null) return null;

      long idleDelta = second[0] - first[0];
      long totalDelta = second[1] - first[1];
      if (totalDelta <= 0) return null;

      return (1.0 - ((double) idleDelta / totalDelta)) * 100.0;
    } catch (Exception e) {
      return null;
    }
  }

  // /proc/stat の cpu 集計行から {idle, total} (jiffies) を取得する
  private static long[] readProcStatTotals() {
    try {
      String line = Files.readAllLines(Paths.get("/proc/stat")).get(0);
      String[] parts = line.trim().split("\\s+");
      long total = 0;
      long idle = 0;
      for (int i = 1; i < parts.length; i++) {
        long v = Long.parseLong(parts[i]);
        total += v;
        if (i == 4) idle = v; // 4番目のフィールドが idle
      }
      return new long[] {idle, total};
    } catch (Exception e) {
      return null;
    }
  }

  private static double[] readMemoryGb() {
    try {
      OperatingSystemMXBean osBean =
          (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
      long total = osBean.getTotalMemorySize();
      long free = osBean.getFreeMemorySize();
      double totalGb = total / 1024.0 / 1024.0 / 1024.0;
      double usedGb = (total - free) / 1024.0 / 1024.0 / 1024.0;
      return new double[] {usedGb, totalGb};
    } catch (Exception e) {
      return null;
    }
  }

  private static double[] readDiskGb(String path) {
    try {
      File root = new File(path);
      long total = root.getTotalSpace();
      long usable = root.getUsableSpace();
      if (total <= 0) return null;
      double totalGb = total / 1024.0 / 1024.0 / 1024.0;
      double usedGb = (total - usable) / 1024.0 / 1024.0 / 1024.0;
      return new double[] {usedGb, totalGb};
    } catch (Exception e) {
      return null;
    }
  }

  // ★ ファイルがない場合は安全なデフォルト値を返す (Docker等でsysfsが無くても落ちない)
  private static String readOrDefault(String path, String defaultValue) {
    try {
      return new String(Files.readAllBytes(Paths.get(path)));
    } catch (IOException e) {
      return defaultValue;
    }
  }

  private static String readIfExists(String path) {
    try {
      return new String(Files.readAllBytes(Paths.get(path))).trim();
    } catch (IOException e) {
      return null;
    }
  }
}
