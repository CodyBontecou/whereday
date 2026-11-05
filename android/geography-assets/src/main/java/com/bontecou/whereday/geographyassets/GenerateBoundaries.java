package com.bontecou.whereday.geographyassets;

import com.github.luben.zstd.ZstdInputStream;
import net.iakovlev.timeshape.TimeZoneEngine;
import net.iakovlev.timeshape.proto.Geojson;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Repackages original rings/IEEE-754 coordinates without simplifying, clipping or quantizing. */
public final class GenerateBoundaries {
    public static final String VERSION="timeshape-2026b.29-f32-polygons-v1";
    public static final int MAGIC=0x57445A31;
    private record Entry(int id,String zone,long bytes,double minLat,double minLon,double maxLat,double maxLon) {}
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).resolve("timezones");
        Files.createDirectories(root);
        // This is a dedicated generated directory; remove only stale polygon assets from a previous build.
        try(var files=Files.list(root)) {
            for(Path file:files.filter(p->p.getFileName().toString().matches("p-[0-9]+\\.bin")).toList()) Files.delete(file);
        }
        List<Entry> entries=new ArrayList<>();
        Set<String> zones=new TreeSet<>();
        long vertices=0,rings=0;
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream raw=Objects.requireNonNull(TimeZoneEngine.class.getResourceAsStream("/data.tar.zstd"));
            InputStream hashed=new DigestInputStream(raw,digest);
            InputStream uncompressed=new ZstdInputStream(hashed);
            TarArchiveInputStream tar=new TarArchiveInputStream(new BufferedInputStream(uncompressed))) {
            TarArchiveEntry source;
            while((source=tar.getNextEntry())!=null) {
                if(!source.isFile()) throw new IOException("Unexpected boundary archive entry");
                byte[] bytes=tar.readNBytes(Math.toIntExact(source.getSize()));
                if(bytes.length!=source.getSize()) throw new EOFException("Truncated source boundary");
                Geojson.Feature feature=Geojson.Feature.parseFrom(bytes);
                String zone=feature.getProperties(0).getValueString();
                zones.add(zone);
                List<Geojson.Polygon> polygons;
                if(feature.getGeometry().hasPolygon()) polygons=List.of(feature.getGeometry().getPolygon());
                else if(feature.getGeometry().hasMultiPolygon()) polygons=feature.getGeometry().getMultiPolygon().getCoordinatesList();
                else throw new IOException("Unsupported source geometry");
                for(Geojson.Polygon polygon:polygons) {
                    if(polygon.getCoordinatesCount()==0) throw new IOException("Empty polygon");
                    int id=entries.size();
                    Path file=root.resolve("p-"+id+".bin");
                    double minLat=90,minLon=180,maxLat=-90,maxLon=-180;
                    try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
                        out.writeInt(polygon.getCoordinatesCount());
                        for(Geojson.LineString ring:polygon.getCoordinatesList()) {
                            if(ring.getCoordinatesCount()<3) throw new IOException("Degenerate source ring");
                            out.writeInt(ring.getCoordinatesCount()); rings++;
                            for(Geojson.Position point:ring.getCoordinatesList()) {
                                double lat=point.getLat(),lon=point.getLon();
                                if(!Double.isFinite(lat)||!Double.isFinite(lon)||lat < -90||lat > 90||lon < -180||lon > 180) throw new IOException("Invalid source coordinate");
                                out.writeFloat(point.getLon()); out.writeFloat(point.getLat()); vertices++;
                                minLat=Math.min(minLat,lat); minLon=Math.min(minLon,lon);
                                maxLat=Math.max(maxLat,lat); maxLon=Math.max(maxLon,lon);
                            }
                        }
                    }
                    entries.add(new Entry(id,zone,Files.size(file),minLat,minLon,maxLat,maxLon));
                }
            }
            // Drain the compressed resource so its hash covers the whole original payload.
            hashed.transferTo(OutputStream.nullOutputStream());
        }
        String hash=HexFormat.of().formatHex(digest.digest());
        try(DataOutputStream out=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(root.resolve("index.bin"))))) {
            out.writeInt(MAGIC); out.writeUTF(VERSION); out.writeUTF(hash); out.writeInt(entries.size());
            for(Entry e:entries) {
                out.writeInt(e.id); out.writeUTF(e.zone); out.writeLong(e.bytes);
                out.writeDouble(e.minLat); out.writeDouble(e.minLon); out.writeDouble(e.maxLat); out.writeDouble(e.maxLon);
            }
        }
        String summary="{\"version\":\""+VERSION+"\",\"source_sha256\":\""+hash+"\",\"zones\":"+zones.size()+",\"polygons\":"+entries.size()+",\"rings\":"+rings+",\"vertices\":"+vertices+",\"largest_polygon_bytes\":"+entries.stream().mapToLong(Entry::bytes).max().orElse(0)+"}\n";
        Files.writeString(root.resolve("provenance.json"),summary);
        System.out.print(summary);
    }
}
