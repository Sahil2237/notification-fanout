import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;

/** Bounded open-arrival acceptance probe. Credentials are read only from environment. */
public final class AcceptanceLoad {
    static final ObjectMapper JSON=new ObjectMapper();
    static int setting(String name,int fallback,int maximum) {
        int value=Integer.parseInt(System.getenv().getOrDefault(name,Integer.toString(fallback)));
        if(value<1 || value>maximum)throw new IllegalArgumentException("Invalid "+name);
        return value;
    }
    public static void main(String[] args) throws Exception {
        int rate=setting("LOAD_RATE",1000,1000),seconds=setting("LOAD_SECONDS",60,600),concurrency=setting("LOAD_CONCURRENCY",256,512);
        String token=Objects.requireNonNull(System.getenv("API_TOKEN"),"API_TOKEN required");
        String run=System.getenv().getOrDefault("LOAD_RUN","load-"+UUID.randomUUID());
        String base=System.getenv().getOrDefault("BASE_URL","http://127.0.0.1:8080");
        if(!base.startsWith("http://127.0.0.1:") && !base.startsWith("http://localhost:") && !"true".equals(System.getenv("ALLOW_REMOTE_LOAD")))throw new IllegalArgumentException("Remote load must be explicit");
        Path output=Path.of(System.getenv().getOrDefault("LOAD_OUTPUT","/tmp/acceptance-load.json"));
        Path stop=Path.of(System.getenv().getOrDefault("LOAD_STOP","/tmp/acceptance-load.stop"));
        var executor=new ThreadPoolExecutor(4,8,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(512,true),new ThreadPoolExecutor.AbortPolicy());
        var slots=new Semaphore(concurrency,true);var histogram=new AtomicLongArray(10001);
        var statuses=new ConcurrentHashMap<String,LongAdder>();var accepted=new LongAdder();var duplicates=new LongAdder();var shed=new LongAdder();var completed=new LongAdder();
        var failure=new AtomicBoolean();var stopped=new AtomicBoolean();long start=System.nanoTime();String started=Instant.now().toString();int offered=0;
        try(var client=HttpClient.newBuilder().executor(executor).version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(3)).build()) {
            for(int index=0;index<rate*seconds;index++) {
                if(index%rate==0 && Files.exists(stop)){stopped.set(true);break;}
                long due=start+(long)index*1_000_000_000L/rate;
                long wait=due-System.nanoTime();if(wait>0)LockSupport.parkNanos(wait);
                offered++;
                if(!slots.tryAcquire()){shed.increment();continue;}
                String key=run+":"+index;
                String producer=UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)).toString();
                String body=JSON.writeValueAsString(Map.of("eventId",producer,"type","validation."+run,"source","acceptance-rate-test","payload",Map.of("synthetic",true,"validationRun",run,"sequence",index)));
                var request=HttpRequest.newBuilder(URI.create(base+"/v1/events")).timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token).header("Content-Type","application/json").header("Idempotency-Key",key).POST(HttpRequest.BodyPublishers.ofString(body)).build();
                long began=System.nanoTime();
                try {
                    client.sendAsync(request,HttpResponse.BodyHandlers.ofString()).whenComplete((response,error)->{
                        try {
                            String status=error==null?Integer.toString(response.statusCode()):"transport_error";
                            statuses.computeIfAbsent(status,k->new LongAdder()).increment();
                            if(error==null && response.statusCode()==202) {
                                var value=JSON.readTree(response.body());
                                if(value.path("duplicate").asBoolean())duplicates.increment();else accepted.increment();
                                UUID.fromString(value.path("event").path("id").asText());
                            }
                        } catch(Exception invalid){failure.set(true);}
                        finally {histogram.incrementAndGet((int)Math.min(10000,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began)));completed.increment();slots.release();}
                    });
                } catch(RuntimeException rejected){statuses.computeIfAbsent("client_rejected",k->new LongAdder()).increment();completed.increment();slots.release();failure.set(true);}
                if(index%Math.max(1,rate*5)==0)write(output,run,rate,seconds,started,start,offered,accepted,duplicates,shed,completed,statuses,histogram,false,stopped.get(),failure.get());
            }
            slots.acquire(concurrency);slots.release(concurrency);
        } finally {executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS);}
        write(output,run,rate,seconds,started,start,offered,accepted,duplicates,shed,completed,statuses,histogram,true,stopped.get(),failure.get());
        System.out.println(Files.readString(output));
    }
    static void write(Path path,String run,int rate,int seconds,String started,long start,int offered,LongAdder accepted,LongAdder duplicates,LongAdder shed,LongAdder completed,Map<String,LongAdder> statuses,AtomicLongArray histogram,boolean done,boolean stopped,boolean invalid) throws Exception {
        var counts=new TreeMap<String,Long>();statuses.forEach((k,v)->counts.put(k,v.sum()));
        var latency=new TreeMap<String,Integer>();
        for(var entry:Map.of("p50",.5,"p95",.95,"p99",.99).entrySet()) {
            long target=(long)Math.ceil(completed.sum()*entry.getValue()),sum=0;int bucket=0;
            for(;bucket<histogram.length()-1;bucket++){sum+=histogram.get(bucket);if(sum>=target)break;}
            latency.put(entry.getKey(),bucket);
        }
        var result=new LinkedHashMap<String,Object>();
        result.put("run_id",run);result.put("started_at",started);result.put("target_events_per_second",rate);result.put("target_duration_seconds",seconds);result.put("offered",offered);result.put("completed",completed.sum());result.put("accepted_new",accepted.sum());result.put("duplicates",duplicates.sum());result.put("generator_shed",shed.sum());result.put("status_counts",counts);result.put("latency_ms",latency);result.put("elapsed_seconds",(System.nanoTime()-start)/1_000_000_000.0);result.put("complete",done);result.put("stopped_by_guard",stopped);result.put("invalid_response",invalid);
        var temporary=path.resolveSibling(path.getFileName()+".next");Files.writeString(temporary,JSON.writeValueAsString(result));Files.move(temporary,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
