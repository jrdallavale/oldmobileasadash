import org.mapsforge.core.model.*;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.datastore.*;
import org.mapsforge.map.reader.MapFile;
import java.io.*;
import java.util.*;

/** Extracts roads, water and place labels inside the ride box from a Mapsforge .map
 *  into a compact binary (see app MapData.kt for the format). */
public class MapExtract {
    static final double MIN_LAT = -25.57, MAX_LAT = -25.35, MIN_LON = -49.65, MAX_LON = -49.41;

    static int classify(Map<String, String> t, boolean closed) {
        String h = t.get("highway");
        if (h != null) {
            switch (h.replace("_link", "")) {
                case "motorway": case "trunk": case "primary": return 8;
                case "secondary": return 7;
                case "tertiary": return 6;
                case "residential": case "unclassified": case "living_street": case "road": case "service": return 5;
                case "track": case "path": case "cycleway": case "bridleway": return 4;
                default: return -1;
            }
        }
        if ("rail".equals(t.get("railway"))) return 3;
        String ww = t.get("waterway");
        if (ww != null && (ww.equals("river") || ww.equals("stream") || ww.equals("canal"))) return 2;
        if (closed) {
            if ("water".equals(t.get("natural")) || "riverbank".equals(ww)
                    || "reservoir".equals(t.get("landuse")) || "basin".equals(t.get("landuse"))) return 0;
            if ("forest".equals(t.get("landuse")) || "wood".equals(t.get("natural")) || "park".equals(t.get("leisure"))) return 1;
        }
        return -1;
    }

    public static void main(String[] a) throws Exception {
        MapFile mf = new MapFile(new File(a[0]));
        byte z = 14;
        int x0 = MercatorProjection.longitudeToTileX(MIN_LON, z), x1 = MercatorProjection.longitudeToTileX(MAX_LON, z);
        int y0 = MercatorProjection.latitudeToTileY(MAX_LAT, z), y1 = MercatorProjection.latitudeToTileY(MIN_LAT, z);
        Set<String> seen = new HashSet<>();
        List<int[]> feats = new ArrayList<>();
        List<Object[]> labels = new ArrayList<>();
        Set<String> labelSeen = new HashSet<>();
        for (int x = x0; x <= x1; x++) for (int y = y0; y <= y1; y++) {
            MapReadResult r = mf.readMapData(new Tile(x, y, z, 256));
            if (r == null) continue;
            for (Way w : r.ways) {
                Map<String, String> t = new HashMap<>();
                for (Tag tg : w.tags) t.put(tg.key, tg.value);
                for (LatLong[] ring : w.latLongs) {
                    if (ring.length < 2) continue;
                    boolean closed = ring[0].equals(ring[ring.length - 1]);
                    int c = classify(t, closed);
                    if (c < 0) continue;
                    StringBuilder key = new StringBuilder().append(c);
                    int[] f = new int[1 + ring.length * 2];
                    f[0] = c;
                    int n = 0; int pl = Integer.MIN_VALUE, po = Integer.MIN_VALUE;
                    for (LatLong p : ring) {
                        int il = (int) Math.round((p.latitude - MIN_LAT) * 1e5), io = (int) Math.round((p.longitude - MIN_LON) * 1e5);
                        il = Math.max(0, Math.min(65535, il)); io = Math.max(0, Math.min(65535, io));
                        if (il == pl && io == po) continue;
                        f[1 + n * 2] = il; f[2 + n * 2] = io; n++; pl = il; po = io;
                        key.append(',').append(il).append(':').append(io);
                    }
                    if (n < 2) continue;
                    if (!seen.add(key.toString())) continue;
                    feats.add(Arrays.copyOf(f, 1 + n * 2));
                }
            }
            for (PointOfInterest p : r.pois) {
                String place = null, name = null;
                for (Tag tg : p.tags) { if (tg.key.equals("place")) place = tg.value; if (tg.key.equals("name")) name = tg.value; }
                if (place == null || name == null) continue;
                int rank;
                switch (place) { case "city": case "town": rank = 0; break; case "suburb": case "village": rank = 1; break;
                    case "neighbourhood": case "hamlet": case "quarter": rank = 2; break; default: continue; }
                double la = p.position.latitude, lo = p.position.longitude;
                if (la < MIN_LAT || la > MAX_LAT || lo < MIN_LON || lo > MAX_LON) continue;
                if (!labelSeen.add(name + rank)) continue;
                labels.add(new Object[]{(int) Math.round((la - MIN_LAT) * 1e5), (int) Math.round((lo - MIN_LON) * 1e5), rank, name});
            }
        }
        feats.sort(Comparator.comparingInt(f -> f[0]));
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(a[1])))) {
            o.writeInt(0x424B4D31); // BKM1
            o.writeDouble(MIN_LAT); o.writeDouble(MIN_LON); o.writeDouble(MAX_LAT); o.writeDouble(MAX_LON);
            o.writeInt(feats.size());
            long pts = 0;
            for (int[] f : feats) {
                int n = (f.length - 1) / 2; pts += n;
                o.writeByte(f[0]); o.writeInt(n);
                for (int i = 1; i < f.length; i++) o.writeShort(f[i]);
            }
            o.writeInt(labels.size());
            for (Object[] l : labels) { o.writeShort((Integer) l[0]); o.writeShort((Integer) l[1]); o.writeByte((Integer) l[2]); o.writeUTF((String) l[3]); }
            System.out.println("features=" + feats.size() + " points=" + pts + " labels=" + labels.size());
        }
        mf.close();
    }
}
