package me.gregorias.dfuntest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import me.gregorias.dfuntest.util.FileUtils;
import me.gregorias.dfuntest.util.SSHClientFactory;
import net.schmizz.sshj.sftp.SFTPClient;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.connection.channel.direct.Session;
import net.schmizz.sshj.connection.channel.direct.Session.Command;
import net.schmizz.sshj.xfer.FileSystemFile;

/**
 * An UNIX environment accessible through SSH with public key.
 */
public class SSHEnvironment extends AbstractConfigurationEnvironment {
  private static final Logger LOGGER = LoggerFactory.getLogger(SSHEnvironment.class);
  private final int mId;
  private final String mRemoteHomePath;

  private final String mUsername;
  private final Path mPrivateKeyPath;

  private final InetAddress mRemoteInetAddress;
  private final Executor mExecutor;
  private final SSHClientFactory mSSHClientFactory;
  private final FileUtils mFileUtils;

  /**
   * @param id Environment's id
   * @param username Username of remote account
   * @param privateKeyPath Path to private key file
   * @param remoteInetAddress Remote host's address
   * @param remoteHomePath Path to remote home where environment will be placed.
   *                       May be relative to user's home.
   * @param executor Executor for running remote commands
   * @param sshClientFactory Factory for SSHClients
   * @param fileUtils Local file utils
   */
  public SSHEnvironment(int id,
      String username,
      Path privateKeyPath,
      InetAddress remoteInetAddress,
      String remoteHomePath,
      Executor executor,
      SSHClientFactory sshClientFactory,
      FileUtils fileUtils) {
    super();
    mId = id;
    mRemoteHomePath = remoteHomePath;

    mUsername = username;
    mPrivateKeyPath = privateKeyPath;

    mRemoteInetAddress = remoteInetAddress;
    mExecutor = executor;
    mSSHClientFactory = sshClientFactory;
    mFileUtils = fileUtils;
  }

  @Override
  public void copyFilesFromLocalDisk(Path srcPath, String destRelPath) throws IOException {
    LOGGER.trace("copyFilesFromLocalDisk({}, {})", srcPath.toString(), destRelPath);
    mkdirs(destRelPath);

    // Must be correct, because mkdirs has passed.
    String remotePath = concatenatePathToHome(destRelPath);

    SSHClient ssh = connectWithSSH();
    try {
      ssh.useCompression();

      ssh.newSCPFileTransfer().upload(new FileSystemFile(srcPath.toFile()), remotePath);
    } finally {
      ssh.disconnect();
    }
  }

  @Override
  public void copyFilesToLocalDisk(String srcRelPath, Path destPath) throws IOException {
    LOGGER.trace("copyFilesToLocalDisk({}, {})", srcRelPath, destPath.toString());
    createDestinationDirectoriesLocally(destPath);

    SSHClient ssh = connectWithSSH();
    try {
      ssh.useCompression();

      String remotePath = concatenatePathToHome(srcRelPath);

      ssh.newSCPFileTransfer().download(remotePath, new FileSystemFile(destPath.toFile()));
    } finally {
      ssh.disconnect();
    }
  }

  @Override
  public String getHostname() {
    return mRemoteInetAddress.getHostName();
  }

  @Override
  public int getId() {
    return mId;
  }

  @Override
  public String getName() {
    return mRemoteInetAddress.getHostName();
  }

  public String getRemoteHomePath() {
    return mRemoteHomePath;
  }

  /**
   * Create directories in environment if they don't exist. If directoryPath consists of several
   * directories all required parent directories are created as well.
   *
   * @param directoryPath path to create
   */
  public void mkdirs(String directoryPath) throws IOException {
    LOGGER.trace("mkdirs({})", directoryPath);
    String finalDirectoryPath = concatenatePathToHome(directoryPath);
    FilenameUtils.normalize(finalDirectoryPath, true);
    if (!finalDirectoryPath.startsWith("/")) {
      // SFTP mkdirs requires to a dot for relative path, otherwise it assumes given path is
      // absolute.
      finalDirectoryPath = "./" + finalDirectoryPath;
    }

    SSHClient ssh = connectWithSSH();
    try {
      try {
        try (SFTPClient sftp = ssh.newSFTPClient()) {
          sftp.mkdirs(finalDirectoryPath);
        }
      } catch (IOException e) {
        // SFTP has failed (on some systems it may be just disabled) revert to mkdir.
        List<String> command = new ArrayList<>();
        command.add("mkdir");
        command.add("-p");
        command.add(finalDirectoryPath);
        String sshHomeDir = ".";
        int exitStatus = runCommand(command, ssh, sshHomeDir);
        if (exitStatus != 0) {
          throw new IOException("Could not create suggested directories.");
        }
      }
    } finally {
      ssh.disconnect();
    }
  }

  @Override
  public void removeFile(String relPath) throws InterruptedException, IOException {
    List<String> command = new ArrayList<>();
    command.add("rm");
    command.add("-rf");
    command.add(relPath);
    RemoteProcess finishedProcess = runCommand(command);
    int exitCode = finishedProcess.waitFor();
    if (exitCode != 0) {
      throw new IOException(String.format("Removal of %s has ended with failure exit code: %d",
          relPath, exitCode));
    }
  }

  /**
   * Run arbitrary command from selected current directory.
   *
   * @param command Command to run.
   * @param pwdDir Directory from which command will be executed
   * @return exit status of command
   * @throws IOException
   */
  public int runCommand(List<String> command, String pwdDir) throws IOException {
    SSHClient ssh = connectWithSSH();
    try {
      return runCommand(command, ssh, pwdDir);
    } catch (IOException e) {
      throw e;
    } finally {
      try {
        ssh.disconnect();
      } catch (IOException ioException) {
        LOGGER.warn("runCommand(): Could not disconnect ssh.", ioException);
      }
    }
  }

  @Override
  public RemoteProcess runCommand(List<String> command) throws InterruptedException, IOException {
    SSHClient ssh;
    ssh = connectWithSSH();
    try {
      ProcessAdapter process = runCommandAndWrapInProcessAdapter(command, ssh);
      process.run();
      process.waitFor();
      return process;
    } catch (InterruptedException | IOException e) {
      try {
        ssh.disconnect();
      } catch (IOException ioException) {
        LOGGER.warn("runCommand(): Could not disconnect ssh.", ioException);
      }
      throw e;
    }
  }

  @Override
  public RemoteProcess runCommandAsynchronously(List<String> command) throws IOException {
    SSHClient ssh;
    ssh = connectWithSSH();
    try {
      ProcessAdapter process = runCommandAndWrapInProcessAdapter(command, ssh);
      mExecutor.execute(process);
      return process;
    } catch (IOException e) {
      try {
        ssh.disconnect();
      } catch (IOException ioException) {
        LOGGER.warn("runCommandAsynchronously(): Could not disconnect ssh.", ioException);
      }
      throw e;
    }
  }

  private static class ProcessAdapter implements RemoteProcess, Runnable {
    private final SSHClient mSSHClient;
    private boolean mHasSSHClientBeenClosed = false;
    private final Session mSSHSession;
    private final Command mCommand;
    private final AtomicBoolean mHasJoined = new AtomicBoolean(false);
    private IOException mIOException;
    private int mExitCode;

    public ProcessAdapter(SSHClient client, Session session, Command command) {
      mSSHClient = client;
      mCommand = command;
      mSSHSession = session;
    }

    @Override
    public void destroy() throws IOException {
      synchronized (mSSHClient) {
        if (!mHasSSHClientBeenClosed) {
          mSSHClient.disconnect();
          mHasSSHClientBeenClosed = true;
        }
      }
    }

    @Override
    public InputStream getErrorStream() {
      return mCommand.getErrorStream();
    }

    @Override
    public InputStream getInputStream() {
      return mCommand.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() {
      return mCommand.getOutputStream();
    }

    @Override
    public void run() {
      try {
        mCommand.join();
        mExitCode = mCommand.getExitStatus();
        mCommand.close();
      } catch (IOException e) {
        LOGGER.error("run(): Could not correctly wait for command finish.", e);
        mIOException = e;
      } finally {
        synchronized (this) {
          mHasJoined.set(true);
          this.notifyAll();
        }

        try {
          mSSHSession.close();
        } catch (IOException e) {
          LOGGER.warn("run(): Could not close SSHSession.", e);
        }

        try {
          synchronized (mSSHClient) {
            if (!mHasSSHClientBeenClosed) {
              mSSHClient.disconnect();
              mHasSSHClientBeenClosed = true;
            }
          }
        } catch (IOException e) {
          LOGGER.warn("run(): Could not disconnect SSHClient.", e);
        }
      }
    }

    @Override
    public int waitFor() throws InterruptedException, IOException {
      synchronized (this) {
        while (!mHasJoined.get()) {
          this.wait();
        }
      }

      if (mIOException != null) {
        throw mIOException;
      } else {
        return mExitCode;
      }
    }
  }

  private String concatenatePathToHome(String relPath) {
    String finalPath = FilenameUtils.concat(mRemoteHomePath, relPath);
    if (finalPath == null) {
      throw new IllegalArgumentException("Provided path can not be concatenated to home path.");
    }
    return finalPath;
  }

  private SSHClient connectWithSSH() throws IOException {
    SSHClient ssh = mSSHClientFactory.newSSHClient();
    ssh.loadKnownHosts();
    ssh.connect(mRemoteInetAddress);
    ssh.authPublickey(mUsername, mPrivateKeyPath.toString());
    return ssh;
  }

  private void createDestinationDirectoriesLocally(Path destPath) throws IOException {
    mFileUtils.createDirectories(destPath);
  }

  private int runCommand(List<String> command, SSHClient ssh, String pwdDir) throws IOException {
    String cdCommand = "cd " + pwdDir + ";";
    try (Session session = ssh.startSession()) {
      Command cmd = session.exec(cdCommand + StringUtils.join(command, ' '));
      cmd.join();
      int exitStatus = cmd.getExitStatus();
      cmd.close();
      return exitStatus;
    }
  }

  private ProcessAdapter runCommandAndWrapInProcessAdapter(List<String> command, SSHClient ssh)
      throws IOException {
    String cdCommand = "cd " + mRemoteHomePath + ";";
    Session session = ssh.startSession();
    try {
      Command cmd = session.exec(cdCommand + StringUtils.join(command, ' '));
      return new ProcessAdapter(ssh, session, cmd);
    } catch (IOException e) {
      session.close();
      throw e;
    }
  }
}
