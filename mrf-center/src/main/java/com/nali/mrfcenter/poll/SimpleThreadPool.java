package com.nali.mrfcenter.poll;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.nali.mrfcore.exception.ConfigException;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A <code>SimpleThreadPool</code> instance should not be used concurrently.
 * <p>
 * This is class is a simple implementation of a thread pool, based on the
 * <code>{@link org.quartz.spi.ThreadPool}</code> interface.
 * </p>
 * 
 * <p>
 * <CODE>Runnable</CODE> objects are sent to the pool with the <code>{@link #runInThread(Runnable)}</code>
 * method, which blocks until a <code>Thread</code> becomes available.
 * </p>
 * 
 * <p>
 * The pool has a fixed number of <code>Thread</code>s, and does not grow or
 * shrink based on demand.
 * </p>
 * 
 * @author James House
 * @author Juergen Donnerstag
 */
public class SimpleThreadPool implements ThreadPool {

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Data members.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    private int count = -1;

    private int prio = Thread.NORM_PRIORITY;

    private boolean isShutdown = false;
    private boolean handoffPending = false;   // 挂起等待

    private boolean inheritLoader = false;

//    private boolean inheritGroup = true;

    private boolean makeThreadsDaemons = false;   // if the worker thread is daemon, default not

    private ThreadGroup threadGroup;

    private final Object nextRunnableLock = new Object();

    private List<WorkerThread> workers;
    private LinkedList<WorkerThread> availWorkers = new LinkedList<WorkerThread>();
    private LinkedList<WorkerThread> busyWorkers = new LinkedList<WorkerThread>();

    private String threadNamePrefix;

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private String threadPoolName;

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Constructors.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * Create a new (unconfigured) <code>SimpleThreadPool</code>.
     * </p>
     * 
     * @see #setThreadCount(int)
     * @see #setThreadPriority(int)
     */
    public SimpleThreadPool() {
    }

    /**
     * <p>
     * Create a new <code>SimpleThreadPool</code> with the specified number
     * of <code>Thread</code> s that have the given priority.
     * </p>
     * @param threadPoolName
     * 			the name for this thread pool
     * @param threadCount
     *          the number of worker <code>Threads</code> in the pool, must
     *          be > 0.
     * @param threadPriority
     *          the thread priority for the worker threads.
     * 
     * @see java.lang.Thread
     */
    public SimpleThreadPool(String threadPoolName, int threadCount, int threadPriority) {
    	setThreadPoolName(threadPoolName);
        setThreadCount(threadCount);
        setThreadPriority(threadPriority);
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * Interface.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    public Logger getLog() {
        return log;
    }

    public int getPoolSize() {
        return getThreadCount();
    }

    /**
     * <p>
     * Set the number of worker threads in the pool - has no effect after
     * <code>initialize()</code> has been called.
     * </p>
     */
    public void setThreadCount(int count) {
        this.count = count;
    }

    /**
     * <p>
     * Get the number of worker threads in the pool.
     * </p>
     */
    public int getThreadCount() {
        return count;
    }

    /**
     * <p>
     * Set the thread priority of worker threads in the pool - has no effect
     * after <code>initialize()</code> has been called.
     * </p>
     */
    public void setThreadPriority(int prio) {
        this.prio = prio;
    }

    /**
     * <p>
     * Get the thread priority of worker threads in the pool.
     * </p>
     */
    public int getThreadPriority() {
        return prio;
    }

    public void setThreadNamePrefix(String prefix) {
        this.threadNamePrefix = prefix;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    /**
     * @return Returns the
     *         threadsInheritContextClassLoaderOfInitializingThread.
     */
    public boolean isThreadsInheritContextClassLoaderOfInitializingThread() {
        return inheritLoader;
    }

    /**
     * @param inheritLoader
     *          The threadsInheritContextClassLoaderOfInitializingThread to
     *          set.
     */
    public void setThreadsInheritContextClassLoaderOfInitializingThread(
            boolean inheritLoader) {
        this.inheritLoader = inheritLoader;
    }

/*    public boolean isThreadsInheritGroupOfInitializingThread() {
        return inheritGroup;
    }

    public void setThreadsInheritGroupOfInitializingThread(
            boolean inheritGroup) {
        this.inheritGroup = inheritGroup;
    }
*/

    /**
     * @return Returns the value of makeThreadsDaemons.
     */
    public boolean isMakeThreadsDaemons() {
        return makeThreadsDaemons;
    }

    /**
     * @param makeThreadsDaemons
     *          The value of makeThreadsDaemons to set.
     */
    public void setMakeThreadsDaemons(boolean makeThreadsDaemons) {
        this.makeThreadsDaemons = makeThreadsDaemons;
    }
    
//    public void setInstanceId(String schedInstId) {
//    }
    
    public String getThreadPoolName() {
    	return threadPoolName;
    }

    public void setThreadPoolName(String threadPoolName) {
        this.threadPoolName = threadPoolName;
    }

    public void initialize() throws ConfigException {

        if(workers != null && workers.size() > 0) // already initialized...
            return;
        
        if(StringUtils.isEmpty(threadPoolName)) {
        	throw new ConfigException("Thread pool's name must be specified");
        }
        if (count <= 0) {
            throw new ConfigException("Thread pool's thread count must be > 0");
        }
        if (prio <= 0 || prio > 9) {
            throw new ConfigException("Thread pool's thread priority must be > 0 and <= 9");
        }

/*        if(isThreadsInheritGroupOfInitializingThread()) {
            threadGroup = Thread.currentThread().getThreadGroup();
        } else {*/
        // follow the threadGroup tree to the root thread group.
        threadGroup = Thread.currentThread().getThreadGroup();
        ThreadGroup parent = threadGroup;
        while ( !parent.getName().equals("main") ) {
            threadGroup = parent;
            parent = threadGroup.getParent();
        }
        threadGroup = new ThreadGroup(parent, threadPoolName + "-SimpleThreadPool");
        if (isMakeThreadsDaemons()) {
            threadGroup.setDaemon(true);
        }
//        }

        if (isThreadsInheritContextClassLoaderOfInitializingThread()) {
            getLog().info("Job execution threads will use class loader of thread: "
                            + Thread.currentThread().getName());
        }

        // create the worker threads and start them
        Iterator<WorkerThread> workerThreads = createWorkerThreads(count).iterator();
        while(workerThreads.hasNext()) {
            WorkerThread wt = workerThreads.next();
            wt.start();
            availWorkers.add(wt);
        }
    }

    protected List<WorkerThread> createWorkerThreads(int createCount) {
        workers = new LinkedList<WorkerThread>();
        for (int i = 1; i<= createCount; ++i) {
            String threadPrefix = getThreadNamePrefix();
            if (threadPrefix == null) {
                threadPrefix = threadPoolName + "_Worker";
            }
            WorkerThread wt = new WorkerThread(this, threadGroup,
                threadPrefix + "-" + i,
                getThreadPriority(),
                isMakeThreadsDaemons());
            // set uncaught exception handler for worker thread to prevent thread leak
            wt.setUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
				
				@Override
				public void uncaughtException(Thread t, Throwable e) {
					getLog().error("Thread {} occurs runtime exception: {}", t.getName(), e.getMessage());
				}
				
			});
            if (isThreadsInheritContextClassLoaderOfInitializingThread()) {
                wt.setContextClassLoader(Thread.currentThread().getContextClassLoader());
            }
            
            workers.add(wt);
        }

        return workers;
    }

    /**
     * <p>
     * Terminate any worker threads in this thread group.
     * </p>
     * 
     * <p>
     * Jobs currently in progress will complete.
     * </p>
     */
    public void shutdown() {
        shutdown(true);
    }

    /**
     * <p>
     * Terminate any worker threads in this thread group.
     * </p>
     * 
     * <p>
     * Jobs currently in progress will complete.
     * </p>
     */
    public void shutdown(boolean waitForJobsToComplete) {

        synchronized (nextRunnableLock) {
            getLog().debug("Shutting down Thread Pool " + threadPoolName);

            isShutdown = true;

            if(workers == null) // in case that the pool wasn't even initialize()ed
                return;

            // signal each worker thread to shut down
            Iterator<WorkerThread> workerThreads = workers.iterator();
            while(workerThreads.hasNext()) {
                WorkerThread wt = workerThreads.next();
                wt.shutdown();
                availWorkers.remove(wt);
            }

            // Give waiting (wait(1000)) worker threads a chance to shut down.
            // Active worker threads will shut down after finishing their
            // current job.
            nextRunnableLock.notifyAll();

            if (waitForJobsToComplete) {
                boolean interrupted = false;
                try {
                    // wait for hand-off in runInThread to complete...
                    while(handoffPending) {
                        try {
                            nextRunnableLock.wait(100);
                        } catch(InterruptedException _) {
                            interrupted = true;
                        }
                    }

                    // Wait until all worker threads are shut down
                    while (busyWorkers.size() > 0) {
                        WorkerThread wt = (WorkerThread) busyWorkers.getFirst();
                        try {
                            getLog().debug(
                                    "Waiting for thread " + wt.getName()
                                            + " to shut down");

                            // note: with waiting infinite time the
                            // application may appear to 'hang'.
                            nextRunnableLock.wait(2000);
                        } catch (InterruptedException _) {
                            interrupted = true;
                        }
                    }

                    workerThreads = workers.iterator();
                    while(workerThreads.hasNext()) {
                        WorkerThread wt = (WorkerThread) workerThreads.next();
                        try {
                            wt.join();
                            workerThreads.remove();
                        } catch (InterruptedException _) {
                            interrupted = true;
                        }
                    }
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }

                getLog().debug("No executing jobs remaining, all threads stopped.");
            }
            getLog().debug("Shutdown of threadpool complete.");
        }
    }

    /**
     * <p>
     * Run the given <code>Runnable</code> object in the next available
     * <code>Thread</code>. If while waiting the thread pool is asked to
     * shut down, the Runnable is executed immediately within a new additional
     * thread.
     * </p>
     * 
     * @param runnable
     *          the <code>Runnable</code> to be added.
     */
    public boolean runInThread(Runnable runnable) {
        if (runnable == null) {
            return false;
        }

        synchronized (nextRunnableLock) {
            handoffPending = true;

            // Wait until a worker thread is available
            while ((availWorkers.size() < 1) && !isShutdown) {
                try {
                    nextRunnableLock.wait(500);
                } catch (InterruptedException ignore) {
                }
            }

            if (!isShutdown) {
                WorkerThread wt = (WorkerThread) availWorkers.removeFirst();
                busyWorkers.add(wt);
                wt.run(runnable);
            } else {
                // If the thread pool is going down, execute the Runnable
                // within a new additional worker thread (no thread from the pool).
                WorkerThread wt = new WorkerThread(this, threadGroup,
                        "WorkerThread-LastJob", prio, isMakeThreadsDaemons(), runnable);
                busyWorkers.add(wt);
                workers.add(wt);
                wt.start();
            }
            nextRunnableLock.notifyAll();
            handoffPending = false;
        }

        return true;
    }

    public int blockForAvailableThreads() {
        synchronized(nextRunnableLock) {

            while((availWorkers.size() < 1 || handoffPending) && !isShutdown) {
                try {
                    nextRunnableLock.wait(500);
                } catch (InterruptedException ignore) {
                }
            }

            return availWorkers.size();
        }
    }

    protected void makeAvailable(WorkerThread wt) {
        synchronized(nextRunnableLock) {
            if(!isShutdown) {
                availWorkers.add(wt);
            }
            busyWorkers.remove(wt);
            nextRunnableLock.notifyAll();
        }
    }

    protected void clearFromBusyWorkersList(WorkerThread wt) {
        synchronized(nextRunnableLock) {
            busyWorkers.remove(wt);
            nextRunnableLock.notifyAll();
        }
    }
    
    public boolean isShutDown() {
    	return isShutdown;
    }
    
    public boolean isHandoffPending() {
    	return handoffPending;
    }
    
    public int getBusyWorkerCount() {
    	return busyWorkers.size();
    }
    
    public int getAvailWorkerCount() {
    	return availWorkers.size();
    }

    /*
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     * 
     * WorkerThread Class.
     * 
     * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
     */

    /**
     * <p>
     * A Worker loops, waiting to execute tasks.
     * </p>
     */
    class WorkerThread extends Thread {

        private final Object lock = new Object();

        // A flag that signals the WorkerThread to terminate.
        private AtomicBoolean run = new AtomicBoolean(true);

        private SimpleThreadPool tp;

        private Runnable runnable = null;
        
        private boolean runOnce = false;

        /**
         * <p>
         * Create a worker thread and start it. Waiting for the next Runnable,
         * executing it, and waiting for the next Runnable, until the shutdown
         * flag is set.
         * </p>
         */
        WorkerThread(SimpleThreadPool tp, ThreadGroup threadGroup, String name,
                     int prio, boolean isDaemon) {
            this(tp, threadGroup, name, prio, isDaemon, null);
        }

        /**
         * <p>
         * Create a worker thread, start it, execute the runnable and terminate
         * the thread (one time execution).
         * </p>
         */
        WorkerThread(SimpleThreadPool tp, ThreadGroup threadGroup, String name,
                     int prio, boolean isDaemon, Runnable runnable) {

            super(threadGroup, name);
            this.tp = tp;
            this.runnable = runnable;
            
            // if we submit Runnable to WorkerThread by constructor, it indicates this WorkerThread won't be reused
            if(runnable != null)   
                runOnce = true;
            
            setPriority(prio);
            setDaemon(isDaemon);
        }

        /**
         * <p>
         * Signal the thread that it should terminate.
         * </p>
         */
        void shutdown() {
            run.set(false);
        }

        public void run(Runnable newRunnable) {
            synchronized(lock) {
                if(runnable != null) {
                    throw new IllegalStateException("Already running a Runnable!");
                }

                runnable = newRunnable;
                lock.notifyAll();
            }
        }

        /**
         * <p>
         * Loop, executing targets as they are received.
         * </p>
         */
        @Override
        public void run() {
            boolean ran = false;   // indicate if this worker thread has executes runnable.run()
            
            while (run.get()) {
                try {
                    synchronized(lock) {
                        while (runnable == null && run.get()) {
                            lock.wait(500);
                        }

                        if (runnable != null) {
                            ran = true;
                            runnable.run();   // do work
                        }
                    }
                } catch (InterruptedException unblock) {
                    // do nothing (loop will terminate if shutdown() was called
                    try {
                        getLog().error("Worker thread was interrupt()'ed.", unblock);
                    } catch(Exception e) {
                        // ignore to help with a tomcat glitch
                    }
                } catch (Throwable exceptionInRunnable) {
                    try {
                        getLog().error("Error while executing the Runnable: ",
                            exceptionInRunnable);
                    } catch(Exception e) {
                        // ignore to help with a tomcat glitch
                    }
                } finally {
                    synchronized(lock) {
                        runnable = null;
                    }
                    // repair the thread in case the runnable mucked it up...
                    if(getPriority() != tp.getThreadPriority()) {
                        setPriority(tp.getThreadPriority());
                    }

                    if (runOnce) {
                    	run.set(false);
                        clearFromBusyWorkersList(this);
                    } else if(ran) {  // WorkerThread "release back" to Thread Pool for reuse
                        ran = false;
                        makeAvailable(this);
                    }

                }
            }

            //if (log.isDebugEnabled())
            try {
                getLog().debug("WorkerThread is shut down.");
            } catch(Exception e) {
                // ignore to help with a tomcat glitch
            }
        }
    }
}