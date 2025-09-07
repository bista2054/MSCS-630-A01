import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * AdvancedShell - A simple shell simulation in Java.
 * Supports basic Unix-like commands, background job management, and external commands.
 */
public class AdvancedShell {

    // Current working directory
    private String currentDirectory;

    // Map of background jobs (jobId -> ProcessJob)
    private final Map<Integer, ProcessJob> jobs;

    // Counter to assign unique job IDs
    private int nextJobId;

    // Scanner for reading user input
    private final Scanner scanner;

    // Flag to control shell loop
    private boolean isRunning;

    /**
     * ProcessJob - represents a background or foreground process.
     */
    private static class ProcessJob {
        int jobId;
        Process process;
        String command;
        boolean isStopped; // For future stop/resume functionality

        ProcessJob(int jobId, Process process, String command) {
            this.jobId = jobId;
            this.process = process;
            this.command = command;
            this.isStopped = false;
        }
    }

    public AdvancedShell() {
        this.currentDirectory = System.getProperty("user.dir");
        this.jobs = new ConcurrentHashMap<>();
        this.nextJobId = 1;
        this.scanner = new Scanner(System.in);
        this.isRunning = true;
    }

    /**
     * Start the shell loop.
     */
    public void start() {
        System.out.println("Advanced Shell Simulation v1.0");
        System.out.println("Type 'exit' to quit the shell");

        while (isRunning) {
            System.out.print("shell> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            // Detect background jobs by trailing '&'
            boolean runInBackground = input.endsWith("&");
            if (runInBackground) input = input.substring(0, input.length() - 1).trim();

            String[] tokens = parseInput(input);
            String command = tokens[0];
            String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);

            try {
                if (runInBackground) {
                    runInBackground(command, args);
                } else {
                    executeCommand(command, args);
                }
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }

            // Cleanup completed background jobs
            cleanupCompletedJobs();
        }
    }

    /**
     * Parse user input into tokens, handling quoted strings.
     */
    private String[] parseInput(String input) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder currentToken = new StringBuilder();

        for (char c : input.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
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

    /**
     * Execute built-in commands or external commands.
     */
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
            default: runExternalCommand(command, args, false); break;
        }
    }

    /**
     * Run a command in the background thread.
     */
    private void runInBackground(String command, String[] args) {
        Thread bgThread = new Thread(() -> {
            try {
                ProcessJob job = runExternalCommand(command, args, true);
                if (job != null) {
                    jobs.put(job.jobId, job);
                    System.out.println("[" + job.jobId + "] " + job.process.pid());
                }
            } catch (Exception e) {
                System.out.println("Error running background job: " + e.getMessage());
            }
        });
        bgThread.start();
    }

    /**
     * Run an external command using ProcessBuilder.
     */
    private ProcessJob runExternalCommand(String command, String[] args, boolean isBackground) {
        try {
            List<String> commandList = new ArrayList<>();
            commandList.add(command);
            commandList.addAll(Arrays.asList(args));

            ProcessBuilder pb = new ProcessBuilder(commandList);
            pb.directory(new File(currentDirectory));
            pb.inheritIO(); // inherit terminal I/O

            Process process = pb.start();

            if (!isBackground) process.waitFor();

            return new ProcessJob(nextJobId++, process, command + " " + String.join(" ", args));
        } catch (IOException e) {
            System.out.println("Command not found: " + command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.out.println("Command interrupted: " + command);
        }
        return null;
    }

    /**
     * Remove completed background jobs and notify user.
     */
    private void cleanupCompletedJobs() {
        Iterator<Map.Entry<Integer, ProcessJob>> iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Integer, ProcessJob> entry = iterator.next();
            ProcessJob job = entry.getValue();
            if (!job.process.isAlive()) {
                iterator.remove();
                System.out.println("[" + job.jobId + "] Done: " + job.command);
            }
        }
    }

    /** ---------------- Built-in Commands ---------------- */

    private void cd(String[] args) {
        if (args.length == 0) {
            currentDirectory = System.getProperty("user.home");
        } else {
            File newDir = args[0].startsWith("/") ? new File(args[0])
                    : new File(currentDirectory, args[0]);
            if (newDir.exists() && newDir.isDirectory()) {
                try { currentDirectory = newDir.getCanonicalPath(); }
                catch (IOException e) { System.out.println("Error: Cannot resolve path"); }
            } else {
                System.out.println("cd: " + args[0] + ": No such directory");
            }
        }
    }

    private void pwd() { System.out.println(currentDirectory); }

    private void exit() {
        isRunning = false;
        jobs.values().forEach(job -> job.process.destroy()); // terminate all background jobs
        System.out.println("Exiting shell...");
    }

    private void echo(String[] args) { System.out.println(String.join(" ", args)); }

    /**
     * Cross-platform clear screen.
     * Uses ANSI codes as fallback if command fails.
     */
    private void clear() {
        try {
            ProcessBuilder pb;
            if (System.getProperty("os.name").contains("Windows")) {
                pb = new ProcessBuilder("cmd", "/c", "cls");
            } else {
                pb = new ProcessBuilder("clear");
                pb.environment().put("TERM", "xterm"); // ensure TERM is set
            }
            Process process = pb.inheritIO().start();
            process.waitFor();
        } catch (IOException | InterruptedException e) {
            // Fallback: ANSI escape codes
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
            } else { System.out.println("cat: " + filename + ": No such file"); }
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
            ProcessHandle.of(pid).ifPresentOrElse(
                    handle -> {
                        if (handle.destroy()) System.out.println("Process " + pid + " terminated");
                        else System.out.println("kill: " + pid + ": Failed to terminate process");
                    },
                    () -> System.out.println("kill: " + pid + ": No such process")
            );
        } catch (NumberFormatException e) { System.out.println("kill: " + args[0] + ": Invalid process id"); }
    }

    private void listJobs() {
        if (jobs.isEmpty()) System.out.println("No background jobs");
        else jobs.values().forEach(job -> System.out.println(
                "[" + job.jobId + "] " + (job.isStopped ? "Stopped" : "Running") + " " + job.command
        ));
    }

    private void foregroundJob(String[] args) {
        if (args.length == 0) { System.out.println("fg: missing job id"); return; }
        try {
            int jobId = Integer.parseInt(args[0]);
            ProcessJob job = jobs.get(jobId);
            if (job != null) {
                System.out.println(job.command);
                job.process.waitFor();
                jobs.remove(jobId);
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
        new AdvancedShell().start();
    }
}
