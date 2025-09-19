import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AdvancedShell - A simple shell simulation in Java.
 * Supports basic Unix-like commands, background job management, external commands,
 * and internal process scheduling with proper timing simulation.
 */
public class AdvancedShell2 {

    // Current working directory
    private String currentDirectory;

    // Map of background jobs (jobId -> ProcessJob)
    private final Map<Integer, ProcessJob> jobs;

    // Counter to assign unique job IDs
    private final AtomicInteger nextJobId;

    // Scanner for reading user input
    private final Scanner scanner;

    // Flag to control shell loop
    private boolean isRunning;

    // Process scheduling related variables
    private final PriorityBlockingQueue<ScheduledProcess> processQueue;
    private static SchedulingAlgorithm currentSchedulingAlgorithm;
    private int timeQuantum; // for Round-Robin
    private final ScheduledExecutorService scheduler;
    private boolean schedulingEnabled;

    // Performance metrics tracking
    private final Map<Integer, ProcessMetrics> processMetrics;
    private final Map<SchedulingAlgorithm, AlgorithmMetrics> algorithmMetrics;

    // Scheduling algorithms
    enum SchedulingAlgorithm {
        ROUND_ROBIN, PRIORITY
    }

    /**
     * ProcessMetrics - tracks performance metrics for individual processes
     */
    private static class ProcessMetrics {
        int pid;
        String command;
        long arrivalTime;
        long startTime;
        long completionTime;
        long totalCpuTime;
        long lastScheduledTime;
        boolean isSimulated;

        ProcessMetrics(int pid, String command, boolean isSimulated) {
            this.pid = pid;
            this.command = command;
            this.arrivalTime = System.currentTimeMillis();
            this.totalCpuTime = 0;
            this.isSimulated = isSimulated;
        }

        long getWaitingTime() {
            return (completionTime - arrivalTime) - totalCpuTime;
        }

        long getTurnaroundTime() {
            return completionTime - arrivalTime;
        }

        long getResponseTime() {
            return startTime - arrivalTime;
        }
    }

    /**
     * AlgorithmMetrics - tracks performance metrics for scheduling algorithms
     */
    private static class AlgorithmMetrics {
        SchedulingAlgorithm algorithm;
        long totalProcesses;
        long totalWaitingTime;
        long totalTurnaroundTime;
        long totalResponseTime;

        AlgorithmMetrics(SchedulingAlgorithm algorithm) {
            this.algorithm = algorithm;
        }

        void addProcessMetrics(ProcessMetrics metrics) {
            totalProcesses++;
            totalWaitingTime += metrics.getWaitingTime();
            totalTurnaroundTime += metrics.getTurnaroundTime();
            totalResponseTime += metrics.getResponseTime();
        }

        double getAverageWaitingTime() {
            return totalProcesses > 0 ? (double) totalWaitingTime / totalProcesses : 0;
        }

        double getAverageTurnaroundTime() {
            return totalProcesses > 0 ? (double) totalTurnaroundTime / totalProcesses : 0;
        }

        double getAverageResponseTime() {
            return totalProcesses > 0 ? (double) totalResponseTime / totalProcesses : 0;
        }
    }

    /**
     * ScheduledProcess - represents a process for internal scheduling
     */
    private static class ScheduledProcess implements Comparable<ScheduledProcess> {
        int pid;
        String command;
        int priority; // lower number = higher priority
        long addedTime;
        long remainingTime; // simulated CPU time required
        long lastScheduled;
        boolean isCompleted;
        boolean isSimulated;
        Process externalProcess; // for external commands

        ScheduledProcess(int pid, String command, int priority, long cpuTimeRequired) {
            this.pid = pid;
            this.command = command;
            this.priority = priority;
            this.addedTime = System.currentTimeMillis();
            this.lastScheduled = 0;
            this.remainingTime = cpuTimeRequired;
            this.isCompleted = false;
            this.isSimulated = true;
            this.externalProcess = null;
        }

        ScheduledProcess(int pid, String command, Process externalProcess) {
            this.pid = pid;
            this.command = command;
            this.priority = 5; // default priority for external commands
            this.addedTime = System.currentTimeMillis();
            this.lastScheduled = 0;
            this.remainingTime = 0;
            this.isCompleted = false;
            this.isSimulated = false;
            this.externalProcess = externalProcess;
        }

        @Override
        public int compareTo(ScheduledProcess other) {
            if (currentSchedulingAlgorithm == SchedulingAlgorithm.PRIORITY) {
                return Integer.compare(this.priority, other.priority);
            }
            return 0; // For Round Robin, order doesn't matter in queue
        }
    }

    /**
     * ProcessJob - represents a background or foreground process.
     */
    private static class ProcessJob {
        int jobId;
        String command;
        boolean isStopped;
        long cpuTimeRequired; // simulated CPU time required
        boolean isSimulated;
        Process externalProcess; // for external commands

        ProcessJob(int jobId, String command, long cpuTimeRequired) {
            this.jobId = jobId;
            this.command = command;
            this.isStopped = false;
            this.cpuTimeRequired = cpuTimeRequired;
            this.isSimulated = true;
            this.externalProcess = null;
        }

        ProcessJob(int jobId, String command, Process externalProcess) {
            this.jobId = jobId;
            this.command = command;
            this.isStopped = false;
            this.cpuTimeRequired = 0;
            this.isSimulated = false;
            this.externalProcess = externalProcess;
        }
    }

    public AdvancedShell() {
        this.currentDirectory = System.getProperty("user.dir");
        this.jobs = new ConcurrentHashMap<>();
        this.nextJobId = new AtomicInteger(1);
        this.scanner = new Scanner(System.in);
        this.isRunning = true;
        this.processQueue = new PriorityBlockingQueue<>();
        this.currentSchedulingAlgorithm = SchedulingAlgorithm.ROUND_ROBIN;
        this.timeQuantum = 100; // default time quantum in ms
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.schedulingEnabled = true;
        this.processMetrics = new ConcurrentHashMap<>();
        this.algorithmMetrics = new ConcurrentHashMap<>();

        // Initialize algorithm metrics
        for (SchedulingAlgorithm algo : SchedulingAlgorithm.values()) {
            algorithmMetrics.put(algo, new AlgorithmMetrics(algo));
        }

        // Start the internal scheduler
        startInternalScheduler();
    }

    /**
     * Start the internal process scheduler
     */
    private void startInternalScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            if (!schedulingEnabled || processQueue.isEmpty()) {
                return;
            }

            try {
                switch (currentSchedulingAlgorithm) {
                    case ROUND_ROBIN:
                        executeRoundRobin();
                        break;
                    case PRIORITY:
                        executePriority();
                        break;
                }
            } catch (Exception e) {
                System.err.println("Scheduler error: " + e.getMessage());
            }
        }, 0, 10, TimeUnit.MILLISECONDS); // Check every 10ms for better responsiveness
    }

    private void executeRoundRobin() {
        ScheduledProcess process = processQueue.poll();
        if (process == null) return;

        long currentTime = System.currentTimeMillis();

        // Initialize metrics if this is the first time the process is scheduled
        if (!processMetrics.containsKey(process.pid)) {
            ProcessMetrics metrics = new ProcessMetrics(process.pid, process.command, process.isSimulated);
            metrics.startTime = currentTime;
            processMetrics.put(process.pid, metrics);
        }

        ProcessMetrics metrics = processMetrics.get(process.pid);
        metrics.lastScheduledTime = currentTime;

        if (process.isSimulated) {
            // Simulate process execution using time sleep
            long executionTime = Math.min(process.remainingTime, timeQuantum);

            try {
                // Simulate CPU execution time
                Thread.sleep(executionTime);

                // Update metrics
                long endTime = System.currentTimeMillis();
                metrics.totalCpuTime += executionTime;
                process.remainingTime -= executionTime;

                // Check if process completed
                if (process.remainingTime <= 0) {
                    process.isCompleted = true;
                    metrics.completionTime = endTime;

                    // Update algorithm metrics
                    AlgorithmMetrics algoMetrics = algorithmMetrics.get(currentSchedulingAlgorithm);
                    algoMetrics.addProcessMetrics(metrics);

                    // Remove from jobs
                    jobs.remove(process.pid);
                    processMetrics.remove(process.pid);

                    System.out.println("Process " + process.pid + " completed: " + process.command);
                    printProcessMetrics(metrics);
                } else {
                    // Put back in queue if not completed
                    processQueue.add(process);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Scheduler interrupted");
            }
        } else {
            // For external processes, just check if they're done
            if (!process.externalProcess.isAlive()) {
                process.isCompleted = true;
                metrics.completionTime = System.currentTimeMillis();

                // Update algorithm metrics
                AlgorithmMetrics algoMetrics = algorithmMetrics.get(currentSchedulingAlgorithm);
                algoMetrics.addProcessMetrics(metrics);

                // Remove from jobs
                jobs.remove(process.pid);
                processMetrics.remove(process.pid);

                System.out.println("Process " + process.pid + " completed: " + process.command);
                printProcessMetrics(metrics);
            } else {
                // Put back in queue if not completed
                processQueue.add(process);
            }
        }
    }

    private void executePriority() {
        // For priority scheduling, we execute the highest priority process to completion
        ScheduledProcess process = processQueue.poll();
        if (process == null) return;

        long currentTime = System.currentTimeMillis();

        // Initialize metrics if this is the first time the process is scheduled
        if (!processMetrics.containsKey(process.pid)) {
            ProcessMetrics metrics = new ProcessMetrics(process.pid, process.command, process.isSimulated);
            metrics.startTime = currentTime;
            processMetrics.put(process.pid, metrics);
        }

        ProcessMetrics metrics = processMetrics.get(process.pid);
        metrics.lastScheduledTime = currentTime;

        if (process.isSimulated) {
            try {
                // Execute the entire process without preemption (for priority scheduling)
                Thread.sleep(process.remainingTime);

                // Update metrics
                long endTime = System.currentTimeMillis();
                metrics.totalCpuTime += process.remainingTime;
                metrics.completionTime = endTime;
                process.isCompleted = true;
                process.remainingTime = 0;

                // Update algorithm metrics
                AlgorithmMetrics algoMetrics = algorithmMetrics.get(currentSchedulingAlgorithm);
                algoMetrics.addProcessMetrics(metrics);

                // Remove from jobs
                jobs.remove(process.pid);
                processMetrics.remove(process.pid);

                System.out.println("Process " + process.pid + " completed: " + process.command);
                printProcessMetrics(metrics);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("Scheduler interrupted");
            }
        } else {
            // For external processes, just check if they're done
            if (!process.externalProcess.isAlive()) {
                process.isCompleted = true;
                metrics.completionTime = System.currentTimeMillis();

                // Update algorithm metrics
                AlgorithmMetrics algoMetrics = algorithmMetrics.get(currentSchedulingAlgorithm);
                algoMetrics.addProcessMetrics(metrics);

                // Remove from jobs
                jobs.remove(process.pid);
                processMetrics.remove(process.pid);

                System.out.println("Process " + process.pid + " completed: " + process.command);
                printProcessMetrics(metrics);
            } else {
                // Put back in queue if not completed
                processQueue.add(process);
            }
        }
    }

    private void printProcessMetrics(ProcessMetrics metrics) {
        System.out.println("Process " + metrics.pid + " Metrics:");
        System.out.println("  Waiting Time: " + metrics.getWaitingTime() + "ms");
        System.out.println("  Turnaround Time: " + metrics.getTurnaroundTime() + "ms");
        System.out.println("  Response Time: " + metrics.getResponseTime() + "ms");
        if (metrics.isSimulated) {
            System.out.println("  CPU Time: " + metrics.totalCpuTime + "ms");
        }
    }

    public void start() {
        System.out.println("Advanced Shell Simulation v2.0 with Internal Process Scheduling");
        System.out.println("Type 'exit' to quit the shell");
        System.out.println("Type 'scheduler' to view scheduling status");
        System.out.println("Type 'setalgorithm <roundrobin|priority>' to change scheduling algorithm");
        System.out.println("Type 'setquantum <ms>' to set time quantum");
        System.out.println("Type 'metrics' to view performance metrics");
        System.out.println("Type 'run <command> <time_ms> [priority]' to run a simulated process");
        System.out.println("Type '<command> &' to run external commands in background");

        while (isRunning) {
            System.out.print("shell> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            boolean runInBackground = input.endsWith("&");
            if (runInBackground) input = input.substring(0, input.length() - 1).trim();

            String[] tokens = parseInput(input);
            String command = tokens[0];
            String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);

            try {
                if (command.equals("run") && args.length >= 2) {
                    // Special handling for simulated processes
                    runSimulatedProcess(args, runInBackground);
                } else if (runInBackground) {
                    runInBackground(command, args);
                } else {
                    executeCommand(command, args);
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }

            cleanupCompletedJobs();
        }

        scheduler.shutdown();
    }

    private void runSimulatedProcess(String[] args, boolean background) {
        try {
            String command = args[0];
            long cpuTime = Long.parseLong(args[1]);
            int priority = 5; // default priority

            if (args.length > 2) {
                priority = Integer.parseInt(args[2]);
            }

            int jobId = nextJobId.getAndIncrement();
            ProcessJob job = new ProcessJob(jobId, command, cpuTime);
            jobs.put(jobId, job);

            ScheduledProcess scheduledProcess = new ScheduledProcess(
                    jobId, command, priority, cpuTime);

            processQueue.add(scheduledProcess);

            System.out.println("[" + jobId + "] " + command + " (CPU time: " + cpuTime + "ms, Priority: " + priority + ")");

            if (!background) {
                // Wait for process to complete
                while (jobs.containsKey(jobId)) {
                    Thread.sleep(100);
                }
            }
        } catch (NumberFormatException e) {
            System.out.println("Invalid arguments for run command. Usage: run <command> <time_ms> [priority]");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String[] parseInput(String input) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder currentToken = new StringBuilder();

        for (char c : input.toCharArray()) {
            if (c == '"') inQuotes = !inQuotes;
            else if (Character.isWhitespace(c) && !inQuotes) {
                if (currentToken.length() > 0) {
                    tokens.add(currentToken.toString());
                    currentToken = new StringBuilder();
                }
            } else {
                currentToken.append(c);
            }
        }
        if (currentToken.length() > 0) tokens.add(currentToken.toString());

        return tokens.toArray(new String[0]);
    }

    private void executeCommand(String command, String[] args) {
        switch (command) {
            case "cd": cd(args); break;
            case "pwd": pwd(); break;
            case "exit": exit(); break;
            case "echo": echo(args); break;
            case "clear": clear(); break;
            case "ls": ls(args); break;
            case "cat": cat(args); break;
            case "mkdir": mkdir(args); break;
            case "rmdir": rmdir(args); break;
            case "rm": rm(args); break;
            case "touch": touch(args); break;
            case "kill": kill(args); break;
            case "jobs": listJobs(); break;
            case "fg": foregroundJob(args); break;
            case "bg": backgroundJob(args); break;
            case "scheduler": showSchedulerStatus(); break;
            case "setalgorithm": setSchedulingAlgorithm(args); break;
            case "setquantum": setTimeQuantum(args); break;
            case "metrics": showMetrics(); break;
            default: runExternalCommand(command, args, false); break;
        }
    }

    private void runInBackground(String command, String[] args) {
        Thread bgThread = new Thread(() -> {
            try {
                ProcessJob job = runExternalCommand(command, args, true);
                if (job != null) {
                    jobs.put(job.jobId, job);

                    // Create a scheduled process for the external command
                    ScheduledProcess scheduledProcess = new ScheduledProcess(
                            job.jobId, job.command, job.externalProcess);

                    processQueue.add(scheduledProcess);

                    System.out.println("[" + job.jobId + "] " + job.command);
                }
            } catch (Exception e) {
                System.out.println("Error running background job: " + e.getMessage());
            }
        });
        bgThread.start();
    }

    private ProcessJob runExternalCommand(String command, String[] args, boolean isBackground) {
        try {
            List<String> commandList = new ArrayList<>();
            commandList.add(command);
            commandList.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(new File(currentDirectory));
            if (isBackground) {
                pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
                pb.redirectError(ProcessBuilder.Redirect.INHERIT);
            } else {
                pb.inheritIO();
            }

            Process process = pb.start();
            int jobId = nextJobId.getAndIncrement();

            if (!isBackground) {
                process.waitFor();
                return null;
            }

            return new ProcessJob(jobId, command + " " + String.join(" ", args), process);
        } catch (IOException e) {
            System.out.println("Command not found: " + command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Command interrupted: " + command);
        }
        return null;
    }

    private void cleanupCompletedJobs() {
        Iterator<Map.Entry<Integer, ProcessJob>> iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ProcessJob> entry = iterator.next();
            ProcessJob job = entry.getValue();

            // For external commands, check if process completed
            if (!job.isSimulated && job.externalProcess != null && !job.externalProcess.isAlive()) {
                iterator.remove();
                processQueue.removeIf(p -> p.pid == job.jobId);
                processMetrics.remove(job.jobId);
                System.out.println("[" + job.jobId + "] Done: " + job.command);
            }
        }
    }

    private void showSchedulerStatus() {
        System.out.println("Scheduler Status:");
        System.out.println("  Algorithm: " + currentSchedulingAlgorithm);
        System.out.println("  Time Quantum: " + timeQuantum + "ms");
        System.out.println("  Active Processes: " + processQueue.size());

        if (!processQueue.isEmpty()) {
            System.out.println("  Queued Processes:");
            for (ScheduledProcess process : processQueue) {
                String type = process.isSimulated ? "Simulated" : "External";
                String details = process.isSimulated ?
                        "(priority: " + process.priority + ", remaining: " + process.remainingTime + "ms)" :
                        "(external process)";
                System.out.println("    [" + process.pid + "] " + process.command + " " + type + " " + details);
            }
        }
    }

    private void showMetrics() {
        System.out.println("Performance Metrics:");
        for (SchedulingAlgorithm algo : SchedulingAlgorithm.values()) {
            AlgorithmMetrics metrics = algorithmMetrics.get(algo);
            if (metrics.totalProcesses > 0) {
                System.out.println("  " + algo + ":");
                System.out.println("    Processes: " + metrics.totalProcesses);
                System.out.println("    Avg Waiting Time: " + String.format("%.2f", metrics.getAverageWaitingTime()) + "ms");
                System.out.println("    Avg Turnaround Time: " + String.format("%.2f", metrics.getAverageTurnaroundTime()) + "ms");
                System.out.println("    Avg Response Time: " + String.format("%.2f", metrics.getAverageResponseTime()) + "ms");
            }
        }

        // Show current queued processes
        if (!processQueue.isEmpty()) {
            System.out.println("  Currently Queued Processes:");
            for (ScheduledProcess process : processQueue) {
                ProcessMetrics metrics = processMetrics.get(process.pid);
                if (metrics != null) {
                    long waitingTime = System.currentTimeMillis() - metrics.arrivalTime - metrics.totalCpuTime;
                    System.out.println("    [" + process.pid + "] " + process.command +
                            " (Waiting: " + waitingTime + "ms)");
                }
            }
        }
    }

    private void setSchedulingAlgorithm(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: setalgorithm <roundrobin|priority>");
            return;
        }

        String algorithm = args[0].toLowerCase();
        switch (algorithm) {
            case "roundrobin":
                currentSchedulingAlgorithm = SchedulingAlgorithm.ROUND_ROBIN;
                System.out.println("Scheduling algorithm set to Round Robin");
                break;
            case "priority":
                currentSchedulingAlgorithm = SchedulingAlgorithm.PRIORITY;
                System.out.println("Scheduling algorithm set to Priority-Based");
                break;
            default:
                System.out.println("Invalid algorithm. Use 'roundrobin' or 'priority'");
                break;
        }
    }

    private void setTimeQuantum(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: setquantum <time_ms>");
            return;
        }

        try {
            timeQuantum = Integer.parseInt(args[0]);
            if (timeQuantum <= 0) {
                System.out.println("Time quantum must be positive");
                return;
            }
            System.out.println("Time quantum set to " + timeQuantum + "ms");
        } catch (NumberFormatException e) {
            System.out.println("Error: time quantum must be an integer");
        }
    }

    private void cd(String[] args) {
        if (args.length == 0) currentDirectory = System.getProperty("user.home");
        else {
            File newDir = args[0].startsWith("/") ? new File(args[0]) : new File(currentDirectory, args[0]);
            if (newDir.exists() && newDir.isDirectory()) {
                try { currentDirectory = newDir.getCanonicalPath(); }
                catch (IOException e) { System.out.println("Error: Cannot resolve path"); }
            } else System.out.println("cd: " + args[0] + ": No such directory");
        }
    }

    private void pwd() { System.out.println(currentDirectory); }

    private void exit() {
        isRunning = false;
        // Kill all external processes
        for (ProcessJob job : jobs.values()) {
            if (!job.isSimulated && job.externalProcess != null) {
                job.externalProcess.destroy();
            }
        }
        processQueue.clear();
        jobs.clear();
        System.out.println("Exiting shell...");
    }

    private void echo(String[] args) { System.out.println(String.join(" ", args)); }

    private void clear() {
        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").contains("Windows")) pb = new ProcessBuilder("cmd", "/c", "cls");
            else {
                pb = new ProcessBuilder("clear");
                pb.environment().put("TERM", "xterm");
            }
            Process process = pb.inheritIO().start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
        }
    }

    private void ls(String[] args) {
        File[] files = new File(currentDirectory).listFiles();
        if (files != null) for (File file : files) System.out.println(file.getName());
    }

    private void cat(String[] args) {
        if (args.length == 0) { System.out.println("cat: missing file operand"); return; }
        for (String filename : args) {
            File file = new File(filename);
            if (!file.isAbsolute()) file = new File(currentDirectory, filename);
            if (file.exists() && file.isFile()) {
                try { Files.readAllLines(file.toPath()).forEach(System.out::println); }
                catch (IOException e) { System.out.println("cat: " + filename + ": Error reading file"); }
            } else System.out.println("cat: " + filename + ": No such file");
        }
    }

    private void mkdir(String[] args) {
        if (args.length == 0) { System.out.println("mkdir: missing operand"); return; }
        for (String dirname : args) {
            File dir = new File(currentDirectory, dirname);
            if (dir.exists()) System.out.println("mkdir: " + dirname + ": File exists");
            else if (!dir.mkdirs()) System.out.println("mkdir: " + dirname + ": Failed to create directory");
        }
    }

    private void rmdir(String[] args) {
        if (args.length == 0) { System.out.println("rmdir: missing operand"); return; }
        for (String dirname : args) {
            File dir = new File(currentDirectory, dirname);
            if (!dir.exists()) System.out.println("rmdir: " + dirname + ": No such directory");
            else if (!dir.isDirectory()) System.out.println("rmdir: " + dirname + ": Not a directory");
            else if (dir.list().length > 0) System.out.println("rmdir: " + dirname + ": Directory not empty");
            else if (!dir.delete()) System.out.println("rmdir: " + dirname + ": Failed to remove directory");
        }
    }

    private void rm(String[] args) {
        if (args.length == 0) { System.out.println("rm: missing operand"); return; }
        for (String filename : args) {
            File file = new File(currentDirectory, filename);
            if (!file.exists()) System.out.println("rm: " + filename + ": No such file");
            else if (file.isDirectory()) System.out.println("rm: " + filename + ": Is a directory");
            else if (!file.delete()) System.out.println("rm: " + filename + ": Failed to remove file");
        }
    }

    private void touch(String[] args) {
        if (args.length == 0) { System.out.println("touch: missing file operand"); return; }
        for (String filename : args) {
            File file = new File(currentDirectory, filename);
            try {
                if (file.exists()) file.setLastModified(System.currentTimeMillis());
                else if (!file.createNewFile()) System.out.println("touch: " + filename + ": Failed to create file");
            } catch (IOException e) {
                System.out.println("touch: " + filename + ": Failed to create file");
            }
        }
    }

    private void kill(String[] args) {
        if (args.length == 0) { System.out.println("kill: missing process id"); return; }
        try {
            int pid = Integer.parseInt(args[0]);
            // For simulated processes, we just remove them from the queue
            processQueue.removeIf(p -> p.pid == pid);

            // For external processes, kill the actual process
            ProcessJob job = jobs.get(pid);
            if (job != null && !job.isSimulated && job.externalProcess != null) {
                job.externalProcess.destroy();
            }

            jobs.remove(pid);
            processMetrics.remove(pid);
            System.out.println("Process " + pid + " terminated");
        } catch (NumberFormatException e) { System.out.println("kill: " + args[0] + ": Invalid process id"); }
    }

    private void listJobs() {
        if (jobs.isEmpty()) System.out.println("No background jobs");
        else {
            for (ProcessJob job : jobs.values()) {
                String type = job.isSimulated ? "Simulated" : "External";
                String status = job.isStopped ? "Stopped" : "Running";
                System.out.println("[" + job.jobId + "] " + status + " " + type + " " + job.command);
            }
        }
    }

    private void foregroundJob(String[] args) {
        if (args.length == 0) { System.out.println("fg: missing job id"); return; }
        try {
            int jobId = Integer.parseInt(args[0]);
            ProcessJob job = jobs.get(jobId);
            if (job != null) {
                System.out.println(job.command);

                if (job.isSimulated) {
                    // Wait for simulated process to complete
                    while (jobs.containsKey(jobId)) {
                        Thread.sleep(100);
                    }
                } else if (job.externalProcess != null) {
                    // Wait for external process to complete
                    job.externalProcess.waitFor();
                    jobs.remove(jobId);
                    processQueue.removeIf(p -> p.pid == jobId);
                    processMetrics.remove(jobId);
                }
            } else System.out.println("fg: " + jobId + ": No such job");
        } catch (NumberFormatException e) { System.out.println("fg: " + args[0] + ": Invalid job id"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void backgroundJob(String[] args) {
        if (args.length == 0) { System.out.println("bg: missing job id"); return; }
        try {
            int jobId = Integer.parseInt(args[0]);
            ProcessJob job = jobs.get(jobId);
            if (job != null && job.isStopped) {
                job.isStopped = false;
                System.out.println("[" + jobId + "] " + job.command + " &");
            } else if (job != null) System.out.println("bg: job " + jobId + " already running in background");
            else System.out.println("bg: " + jobId + ": No such job");
        } catch (NumberFormatException e) { System.out.println("bg: " + args[0] + ": Invalid job id"); }
    }

    public static void main(String[] args) {
        new AdvancedShell2().start();
    }
}