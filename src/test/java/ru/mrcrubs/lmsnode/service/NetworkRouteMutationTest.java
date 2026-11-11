package ru.mrcrubs.lmsnode.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.mrcrubs.lmsnode.config.NodeProperties;
import ru.mrcrubs.lmsnode.downloader.*;
import ru.mrcrubs.lmsnode.infra.InMemoryJobRepository;
import ru.mrcrubs.lmsnode.model.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class NetworkRouteMutationTest {
    private NetworkRoutes routes(){return new NetworkRoutes(List.of(new NetworkRoutes.Entry("eu","Europe","http://fixture:private@127.0.0.1:34000")));}
    @Test void changingRunningRouteStopsOldTransportBeforeRestartAndIgnoresOldCompletion(@TempDir Path directory)throws Exception{
        var started=new CountDownLatch(1);var resumed=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var runs=new AtomicInteger();var active=new AtomicInteger();var maximum=new AtomicInteger();var firstContext=new AtomicReference<DownloadExecutionContext>();
        Downloader downloader=new Downloader(){
            public JobType id(){return JobType.DIRECT;}
            public DownloadResult download(DownloadRequest request,DownloadExecutionContext context,java.util.function.Consumer<ProgressUpdate> progress)throws Exception{
                maximum.accumulateAndGet(active.incrementAndGet(),Math::max);int run=runs.incrementAndGet();
                try{if(run==1){firstContext.set(context);started.countDown();while(!context.isCanceled())Thread.sleep(5);progress.accept(new ProgressUpdate(99.0,null,null,null,"stale"));return new DownloadResult(null,"old completed");}
                    assertEquals("eu",request.networkRoute().id);resumed.countDown();finish.await(5,TimeUnit.SECONDS);return new DownloadResult(null,"new completed");
                }finally{active.decrementAndGet();}
            }
        };
        var properties=new NodeProperties();properties.setDownloadDir(directory.toString());properties.setMaxParallel(2);
        var service=new JobService(List.of(downloader),properties,new InMemoryJobRepository(),routes());
        try{
            UUID id=service.create(JobType.DIRECT,"https://course.test/lesson");assertTrue(started.await(3,TimeUnit.SECONDS));
            assertThrows(IllegalArgumentException.class,()->service.setNetworkRoute(id,"unknown"));assertFalse(firstContext.get().isCanceled());
            service.setNetworkRoute(id,"eu");assertTrue(resumed.await(3,TimeUnit.SECONDS));assertEquals(1,maximum.get());assertEquals(2,runs.get());assertEquals(JobStatus.RUNNING,service.get(id).getStatus());assertEquals("eu",service.get(id).getNetworkRouteId());assertNotEquals(99.0,service.get(id).getPercent());
            finish.countDown();long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(service.get(id).getStatus()!=JobStatus.DONE&&System.nanoTime()<until)Thread.sleep(5);assertEquals(JobStatus.DONE,service.get(id).getStatus());
            assertThrows(IllegalStateException.class,()->service.setNetworkRoute(id,null));
        }finally{finish.countDown();service.shutdown();}
    }
    @Test void changingPausedRouteDoesNotStartAndCanResetToAutomatic(@TempDir Path directory){
        var properties=new NodeProperties();properties.setDownloadDir(directory.toString());var service=new JobService(List.of(),properties,new InMemoryJobRepository(),routes());
        try{UUID id=service.create(JobType.DIRECT,"https://course.test/lesson",null,false);service.setNetworkRoute(id,"eu");assertEquals(JobStatus.PAUSED,service.get(id).getStatus());assertEquals("Europe",service.get(id).getNetworkRouteLabel());service.setNetworkRoute(id,"");assertNull(service.get(id).getNetworkRouteId());assertEquals(JobStatus.PAUSED,service.get(id).getStatus());service.cancel(id);assertThrows(IllegalStateException.class,()->service.setNetworkRoute(id,"eu"));}finally{service.shutdown();}
    }
}
