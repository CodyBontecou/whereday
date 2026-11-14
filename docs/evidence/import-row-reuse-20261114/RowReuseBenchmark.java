import com.bontecou.whereday.platform.*;
import com.bontecou.whereday.domain.TrackPoint;
import java.lang.management.ManagementFactory;

/** Isolates three reads of each immutable staged row in pages of200. Not a full import/deadline test. */
public final class RowReuseBenchmark {
    static volatile long sink;
    static final String PAYLOAD="{\"id\":\"synthetic-point\",\"coordinate\":{\"latitude\":18.0,\"longitude\":-66.0},\"time\":[0,123456789],\"accuracy\":1.5,\"altitude\":-2.5,\"speed\":3.5,\"outlier\":true,\"countryCode\":\"PR\",\"timeZone\":\"America/Puerto_Rico\"}";
    static void run(int count) {
        long sum=0;
        for(int first=0;first<count;first+=200) {
            ImportStageRow[] rows=new ImportStageRow[Math.min(200,count-first)];
            for(int i=0;i<rows.length;i++)rows[i]=new ImportStageRow(ImportRecordKind.point,"synthetic-point",PAYLOAD,null,true);
            for(int read=0;read<3;read++)for(ImportStageRow row:rows) {
                TrackPoint value=row.point();
                if(value.getAccuracy()!=1.5 || value.getTime().getNano()!=123456789 || value.getCoordinate().getLongitude()!=-66.0)throw new AssertionError("Decoded metadata changed");
                sum+=value.getTime().getNano();
            }
        }
        if(sum!=123456789L*count*3)throw new AssertionError("Read count changed");sink=sum;
    }
    public static void main(String[] args) {
        int count=200000;run(20000);
        java.lang.management.ThreadMXBean clock=ManagementFactory.getThreadMXBean();
        for(int trial=0;trial<3;trial++) {
            long cpu=clock.getCurrentThreadCpuTime(),wall=System.nanoTime();run(count);
            System.out.println("{\"trial\":"+trial+",\"rows\":"+count+",\"readsPerRow\":3,\"pageLimit\":200,\"wallNanos\":"+(System.nanoTime()-wall)+",\"threadCpuNanos\":"+(clock.getCurrentThreadCpuTime()-cpu)+",\"checksum\":"+sink+"}");
        }
    }
}
