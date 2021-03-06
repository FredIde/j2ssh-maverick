/**
 * Copyright 2003-2016 SSHTOOLS Limited. All Rights Reserved.
 *
 * For product documentation visit https://www.sshtools.com/
 *
 * This file is part of J2SSH Maverick.
 *
 * J2SSH Maverick is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * J2SSH Maverick is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with J2SSH Maverick.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.sshtools.sftp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.StringTokenizer;
import java.util.Vector;

import com.sshtools.events.Event;
import com.sshtools.events.EventServiceImplementation;
import com.sshtools.events.J2SSHEventCodes;
import com.sshtools.logging.Log;
import com.sshtools.ssh.ChannelOpenException;
import com.sshtools.ssh.Client;
import com.sshtools.ssh.SshClient;
import com.sshtools.ssh.SshException;
import com.sshtools.ssh.SshIOException;
import com.sshtools.ssh.SshSession;
import com.sshtools.ssh2.Ssh2Session;
import com.sshtools.util.EOLProcessor;
import com.sshtools.util.IOUtil;
import com.sshtools.util.UnsignedInteger32;
import com.sshtools.util.UnsignedInteger64;

/**
 * <p>
 * Implements a Secure File Transfer (SFTP) client.
 * </p>
 * 
 * @author Lee David Painter
 */
public class SftpClient implements Client {
	SftpSubsystemChannel sftp;
	String cwd;
	String lcwd;

	private int blocksize = 4096;
	private int asyncRequests = 100;
	private int buffersize = -1;

	// Default permissions is determined by default_permissions ^ umask
	int umask = 0022;

	/*
	 * public static final int TYPE_REGULAR = 1; public static final int
	 * TYPE_DIRECTORY = 2; public static final int TYPE_SYMLINK = 3; public
	 * static final int TYPE_SPECIAL = 4; public static final int TYPE_UNKNOWN =
	 * 5;
	 */

	/**
	 * Instructs the client to use a binary transfer mode when used with {@link
	 * setTransferMode(int)}
	 */
	public static final int MODE_BINARY = 1;

	/**
	 * Instructs the client to use a text transfer mode when used with {@link
	 * setTransferMode(int)}.
	 */
	public static final int MODE_TEXT = 2;

	/**
	 * <p>
	 * Specifies that the remote server is using \r\n as its newline convention
	 * when used with {@link setRemoteEOL(int)}
	 * </p>
	 */
	public static final int EOL_CRLF = EOLProcessor.TEXT_CRLF;

	/**
	 * <p>
	 * Specifies that the remote server is using \n as its newline convention
	 * when used with {@link setRemoteEOL(int)}
	 * </p>
	 */
	public static final int EOL_LF = EOLProcessor.TEXT_LF;

	/**
	 * <p>
	 * Specifies that the remote server is using \r as its newline convention
	 * when used with {@link setRemoteEOL(int)}
	 * </p>
	 */
	public static final int EOL_CR = EOLProcessor.TEXT_CR;

	private int eolMode = EOL_CRLF;

	private int transferMode = MODE_BINARY;

	public SftpClient(SshClient ssh) throws SftpStatusException, SshException,
			ChannelOpenException {
		this(ssh, SftpSubsystemChannel.MAX_VERSION);
	}

	public SftpClient(SshSession session) throws SftpStatusException,
			SshException {
		this(session, SftpSubsystemChannel.MAX_VERSION);
	}

	public SftpClient(SshSession session, int Max_Version)
			throws SftpStatusException, SshException {
		initSftp(session, Max_Version);
	}

	/**
	 * <p>
	 * Constructs the SFTP client with a given channel event listener.
	 * </p>
	 * 
	 * @param ssh
	 *            the <code>SshClient</code> instance
	 * @param Max_Version
	 *            the maximum SFTP protocol version to use
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws ChannelOpenException
	 */
	public SftpClient(SshClient ssh, int Max_Version)
			throws SftpStatusException, SshException, ChannelOpenException {

		SshSession session = ssh.openSessionChannel();

		/**
		 * Start the SFTP server
		 */
		Ssh2Session ssh2 = (Ssh2Session) session;
		if (!ssh2.startSubsystem("sftp")) {
			if (Log.isDebugEnabled()) {
				Log.debug(this,
						"The SFTP subsystem failed to start, attempting to execute provider "
								+ ssh.getContext().getSFTPProvider());
			}
			// We could not start the subsystem try to fallback to the
			// provider specified by the user
			if (!ssh2.executeCommand(ssh.getContext().getSFTPProvider())) {
				ssh2.close();
				throw new SshException(
						"Failed to start SFTP subsystem or SFTP provider "
								+ ssh.getContext().getSFTPProvider(),
						SshException.CHANNEL_FAILURE);
			}
		}

		initSftp(session, Max_Version);
	}

	private void initSftp(SshSession session, int Max_Version)
			throws SftpStatusException, SshException {
		sftp = new SftpSubsystemChannel(session, Max_Version);

		try {
			sftp.initialize();
		} catch (UnsupportedEncodingException ex) {

		}
		// Get the users default directory
		cwd = sftp.getDefaultDirectory();
		String homeDir = "";
		try {
			homeDir = System.getProperty("user.home");
		} catch (SecurityException e) {
			// ignore
		}
		lcwd = homeDir;
		EventServiceImplementation.getInstance().fireEvent(
				new Event(this, J2SSHEventCodes.EVENT_SFTP_SESSION_STARTED,
						true));
	}

	/**
	 * Sets the block size used when transferring files, defaults to the
	 * optimized setting of 32768. You should not increase this value as the
	 * remote server may not be able to support higher blocksizes.
	 * 
	 * @param blocksize
	 */
	public void setBlockSize(int blocksize) {
		if (blocksize < 512) {
			throw new IllegalArgumentException(
					"Block size must be greater than 512");
		}
		this.blocksize = blocksize;
	}

	/**
	 * Returns the instance of the SftpSubsystemChannel used by this class
	 * 
	 * @return the SftpSubsystemChannel instance
	 */
	public SftpSubsystemChannel getSubsystemChannel() {
		return sftp;
	}

	/**
	 * <p>
	 * Sets the transfer mode for current operations. The valid modes are:<br>
	 * <br>
	 * {@link #MODE_BINARY} - Files are transferred in binary mode and no
	 * processing of text files is performed (default mode).<br>
	 * <br>
	 * {@link #MODE_TEXT} - For servers supporting version 4+ of the SFTP
	 * protocol files are transferred in text mode. For earlier protocol
	 * versions the files are transfered in binary mode but the client performs
	 * processing of text; if files are written to the remote server the client
	 * ensures that the line endings conform to the remote EOL mode set using
	 * {@link setRemoteEOL(int)}. For files retrieved from the server the EOL
	 * policy is based upon System policy as defined by the "line.seperator"
	 * system property.
	 * </p>
	 * 
	 * @param transferMode
	 *            int
	 */
	public void setTransferMode(int transferMode) {
		if (transferMode != MODE_BINARY && transferMode != MODE_TEXT)
			throw new IllegalArgumentException(
					"Mode can only be either binary or text");

		this.transferMode = transferMode;

		if (Log.isDebugEnabled()) {
			Log.debug(this, "Transfer mode set to "
					+ (transferMode == MODE_BINARY ? "binary" : "text"));
		}
	}

	/**
	 * <p>
	 * When connected to servers running SFTP version 3 (or less) the remote EOL
	 * type needs to be explicitly set because there is no reliable way for the
	 * client to determine the type of EOL for text files. In versions 4+ a
	 * mechanism is provided and this setting is overridden.
	 * </p>
	 * 
	 * <p>
	 * Valid values for this method are {@link EOL_CRLF} (default),
	 * {@link EOL_CR}, and {@link EOL_LF}.
	 * </p>
	 * 
	 * @param eolMode
	 *            int
	 */
	public void setRemoteEOL(int eolMode) {
		this.eolMode = eolMode;

		if (Log.isDebugEnabled()) {
			Log.debug(this, "Remote EOL set to "
					+ (eolMode == EOL_CRLF ? "CRLF" : (eolMode == EOL_CR ? "CR"
							: "LF")));
		}

	}

	/**
	 * 
	 * @return int
	 */
	public int getTransferMode() {
		return transferMode;
	}

	/**
	 * Set the size of the buffer which is used to read from the local file
	 * system. This setting is used to optimize the writing of files by allowing
	 * for a large chunk of data to be read in one operation from a local file.
	 * The previous version simply read each block of data before sending
	 * however this decreased performance, this version now reads the file into
	 * a temporary buffer in order to reduce the number of local filesystem
	 * reads. This increases performance and so this setting should be set to
	 * the highest value possible. The default setting is negative which means
	 * the entire file will be read into a temporary buffer.
	 * 
	 * @param buffersize
	 */
	public void setBufferSize(int buffersize) {
		this.buffersize = buffersize;

		if (Log.isDebugEnabled()) {
			Log.debug(this, "Buffer size set to " + buffersize);
		}

	}

	/**
	 * Set the maximum number of asynchronous requests that are outstanding at
	 * any one time. This setting is used to optimize the reading and writing of
	 * files to/from the remote file system when using the get and put methods.
	 * The default for this setting is 100.
	 * 
	 * @param asyncRequests
	 */
	public void setMaxAsyncRequests(int asyncRequests) {
		if (asyncRequests < 1) {
			throw new IllegalArgumentException(
					"Maximum asynchronous requests must be greater or equal to 1");
		}
		this.asyncRequests = asyncRequests;

		if (Log.isDebugEnabled()) {
			Log.debug(this, "Max async requests set to "
					+ asyncRequests);
		}

	}

	/**
	 * Sets the umask used by this client. <blockquote>
	 * 
	 * <pre>
	 * To give yourself full permissions for both files and directories and
	 * prevent the group and other users from having access:
	 * 
	 *   umask(077);
	 * 
	 * This subtracts 077 from the system defaults for files and directories
	 * 666 and 777. Giving a default access permissions for your files of
	 * 600 (rw-------) and for directories of 700 (rwx------).
	 * 
	 * To give all access permissions to the group and allow other users read
	 * and execute permission:
	 * 
	 *   umask(002);
	 * 
	 * This subtracts 002 from the system defaults to give a default access permission
	 * for your files of 664 (rw-rw-r--) and for your directories of 775 (rwxrwxr-x).
	 * 
	 * To give the group and other users all access except write access:
	 * 
	 *   umask(022);
	 * 
	 * This subtracts 022 from the system defaults to give a default access permission
	 * for your files of 644 (rw-r--r--) and for your directories of 755 (rwxr-xr-x).
	 * </pre>
	 * 
	 * </blockquote>
	 * 
	 * @param umask
	 * @return the previous umask value
	 */
	public int umask(int umask) {
		int old = this.umask;
		this.umask = umask;

		if (Log.isDebugEnabled()) {
			Log.debug(this, "umask " + umask);
		}

		return old;
	}

	public SftpFile openFile(String fileName) throws SftpStatusException,
			SshException {
		if (transferMode == MODE_TEXT && sftp.getVersion() > 3) {
			return sftp.openFile(resolveRemotePath(fileName),
					SftpSubsystemChannel.OPEN_READ
							| SftpSubsystemChannel.OPEN_TEXT);
		}
		return sftp.openFile(resolveRemotePath(fileName),
				SftpSubsystemChannel.OPEN_READ);
	}

	/**
	 * <p>
	 * Changes the working directory on the remote server, or the user's default
	 * directory if <code>null</code> or any empty string is provided as the
	 * directory path. The user's default directory is typically their home
	 * directory but is dependent upon server implementation.
	 * </p>
	 * 
	 * @param dir
	 *            the new working directory
	 * 
	 * @throws IOException
	 *             if an IO error occurs or the file does not exist
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public void cd(String dir) throws SftpStatusException, SshException {
		String actual;

		if (dir == null || dir.equals("")) {
			actual = sftp.getDefaultDirectory();
		} else {
			actual = resolveRemotePath(dir);
			actual = sftp.getAbsolutePath(actual);
		}

		if (!actual.equals("")) {
			SftpFileAttributes attr = sftp.getAttributes(actual);

			if (!attr.isDirectory()) {
				throw new SftpStatusException(
						SftpStatusException.SSH_FX_FAILURE, dir
								+ " is not a directory");
			}
		}

		if (Log.isDebugEnabled()) {
			Log.debug(this, "Changing dir from " + cwd + " to "
					+ (actual.equals("") ? "user default dir" : actual));
		}

		cwd = actual;
	}

	// protected void finalize() throws Throwable {
	//
	// if(sftp!=null)
	// sftp.close();
	// sftp = null;
	// super.finalize();
	//
	// }

	/**
	 * <p>
	 * Get the default directory (or HOME directory)
	 * </p>
	 * 
	 * @return String
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public String getDefaultDirectory() throws SftpStatusException,
			SshException {
		return sftp.getDefaultDirectory();
	}

	/**
	 * Change the working directory to the parent directory
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public void cdup() throws SftpStatusException, SshException {

		SftpFile cd = sftp.getFile(cwd);

		SftpFile parent = cd.getParent();

		if (parent != null)
			cwd = parent.getAbsolutePath();

	}

	private File resolveLocalPath(String path) {
		File f = new File(path);

		if (!f.isAbsolute()) {
			f = new File(lcwd, path);
		}

		return f;
	}

	private boolean isWindowsRoot(String path) {
		path = path.trim();
		// true if path>2 and starts with a letter followed by a ':' followed by
		// '/' or '\\'
		return path.length() > 2
				&& (((path.charAt(0) >= 'a' && path.charAt(0) <= 'z') || (path
						.charAt(0) >= 'A' && path.charAt(0) <= 'Z'))
						&& path.charAt(1) == ':' && path.charAt(2) == '/' || path
						.charAt(2) == '\\');
	}

	/**
	 * some devices have unusual file system roots such as "flash:", customRoots
	 * contains these. If a device uses roots like this, and folder traversal on
	 * the device is required then it must have its root stored in customRoots
	 */
	private Vector<String> customRoots = new Vector<String>();

	/**
	 * Add a custom file system root path such as "flash:"
	 * 
	 * @param rootPath
	 */
	public void addCustomRoot(String rootPath) {
		customRoots.addElement(rootPath);
	}

	/**
	 * Remove a custom file system root path such as "flash:"
	 * 
	 * @param rootPath
	 */
	public void removeCustomRoot(String rootPath) {
		customRoots.removeElement(rootPath);
	}

	/**
	 * Tests whether path starts with a custom file system root.
	 * 
	 * @param path
	 * @return <em>true</em> if path starts with an element of customRoots,
	 *         <em>false</em> otherwise
	 */
	private boolean startsWithCustomRoot(String path) {
		for (Enumeration<String> it = customRoots.elements(); it != null
				&& it.hasMoreElements();) {
			if (path.startsWith(it.nextElement())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * returns the canonical form of path, if path doesn't start with one of
	 * '/';cwd;a customRoot; or is a WindowsRoot then prepend cwd to path
	 * 
	 * @param path
	 * @return canonical form of path
	 * @throws SftpStatusException
	 */
	private String resolveRemotePath(String path) throws SftpStatusException {
		verifyConnection();

		String actual;
		if (!path.startsWith("/") && !path.startsWith(cwd)
				&& !isWindowsRoot(path) && !startsWithCustomRoot(path)) {
			actual = cwd + (cwd.endsWith("/") ? "" : "/") + path;
		} else {
			actual = path;
		}

		if (!actual.equals("/") && actual.endsWith("/")) {
			return actual.substring(0, actual.length() - 1);
		} else {
			return actual;
		}
	}

	private void verifyConnection() throws SftpStatusException {
		if (sftp.isClosed()) {
			throw new SftpStatusException(
					SftpStatusException.SSH_FX_CONNECTION_LOST,
					"The SFTP connection has been closed");
		}
	}

	/**
	 * <p>
	 * Creates a new directory on the remote server. This method will throw an
	 * exception if the directory already exists. To create directories and
	 * disregard any errors use the <code>mkdirs</code> method.
	 * </p>
	 * 
	 * @param dir
	 *            the name of the new directory
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public void mkdir(String dir) throws SftpStatusException, SshException {
		String actual = resolveRemotePath(dir);

		if (Log.isDebugEnabled()) {
			Log.debug(this, "Creating dir " + dir);
		}

		try {
			sftp.getAttributes(actual);
		} catch (SftpStatusException ex) {
			// only create the directory if catch an exception with code file
			// not found
			SftpFileAttributes newattrs = new SftpFileAttributes(sftp,
					SftpFileAttributes.SSH_FILEXFER_TYPE_DIRECTORY);
			newattrs.setPermissions(new UnsignedInteger32(0777 ^ umask));
			sftp.makeDirectory(actual, newattrs);
			return;
		}

		if (Log.isDebugEnabled()) {
			Log.debug(this, "A file/folder with name " + dir
					+ " already exists!");
		}

		throw new SftpStatusException(SftpStatusException.SSH_FX_FAILURE,
				"File already exists named " + dir);

	}

	/**
	 * <p>
	 * Create a directory or set of directories. This method will not fail even
	 * if the directories exist. It is advisable to test whether the directory
	 * exists before attempting an operation by using <a
	 * href="#stat(java.lang.String)">stat</a> to return the directories
	 * attributes.
	 * </p>
	 * 
	 * @param dir
	 *            the path of directories to create.
	 */
	public void mkdirs(String dir) throws SftpStatusException, SshException {
		StringTokenizer tokens = new StringTokenizer(dir, "/");
		String path = dir.startsWith("/") ? "/" : "";

		while (tokens.hasMoreElements()) {
			path += (String) tokens.nextElement();

			try {
				stat(path);
			} catch (SftpStatusException ex) {
				try {
					mkdir(path);
				} catch (SftpStatusException ex2) {
					if (ex2.getStatus() == SftpStatusException.SSH_FX_PERMISSION_DENIED)
						throw ex2;
				}
			}

			path += "/";
		}
	}

	/**
	 * Determine whether the file object is pointing to a symbolic link that is
	 * pointing to a directory.
	 * 
	 * @return boolean
	 */
	public boolean isDirectoryOrLinkedDirectory(SftpFile file)
			throws SftpStatusException, SshException {
		return file.isDirectory()
				|| (file.isLink() && stat(file.getAbsolutePath()).isDirectory());
	}

	/**
	 * <p>
	 * Returns the absolute path name of the current remote working directory.
	 * </p>
	 * 
	 * @return the absolute path of the remote working directory.
	 */
	public String pwd() {
		return cwd;
	}

	/**
	 * <p>
	 * List the contents of the current remote working directory.
	 * </p>
	 * 
	 * <p>
	 * Returns a list of <a
	 * href="../../maverick/ssh2/SftpFile.html">SftpFile</a> instances for the
	 * current working directory.
	 * </p>
	 * 
	 * @return a list of SftpFile for the current working directory
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * 
	 */
	public SftpFile[] ls() throws SftpStatusException, SshException {
		return ls(cwd);
	}

	/**
	 * <p>
	 * List the contents remote directory.
	 * </p>
	 * 
	 * <p>
	 * Returns a list of <a
	 * href="../../maverick/ssh2/SftpFile.html">SftpFile</a> instances for the
	 * remote directory.
	 * </p>
	 * 
	 * @param path
	 *            the path on the remote server to list
	 * 
	 * @return a list of SftpFile for the remote directory
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public SftpFile[] ls(String path) throws SftpStatusException, SshException {

		String actual = resolveRemotePath(path);

		if (Log.isDebugEnabled()) {
			Log.debug(this, "Listing files for " + actual);
		}

		SftpFile file = sftp.openDirectory(actual);
		Vector<SftpFile> children = new Vector<SftpFile>();
		while (sftp.listChildren(file, children) > -1) {
			;
		}
		file.close();
		SftpFile[] files = new SftpFile[children.size()];
		int index = 0;
		for (Enumeration<SftpFile> e = children.elements(); e.hasMoreElements();) {
			files[index++] = e.nextElement();
		}
		return files;
	}

	/**
	 * <p>
	 * Changes the local working directory.
	 * </p>
	 * 
	 * @param path
	 *            the path to the new working directory
	 * 
	 * @throws SftpStatusException
	 */
	public void lcd(String path) throws SftpStatusException {
		File actual;

		if (!isLocalAbsolutePath(path)) {
			actual = new File(lcwd, path);
		} else {
			actual = new File(path);
		}

		if (!actual.isDirectory()) {
			throw new SftpStatusException(SftpStatusException.SSH_FX_FAILURE,
					path + " is not a directory");
		}

		try {
			lcwd = actual.getCanonicalPath();
		} catch (IOException ex) {
			throw new SftpStatusException(SftpStatusException.SSH_FX_FAILURE,
					"Failed to canonicalize path " + path);
		}
	}

	private static boolean isLocalAbsolutePath(String path) {
		return (new File(path)).isAbsolute();
	}

	/**
	 * <p>
	 * Returns the absolute path to the local working directory.
	 * </p>
	 * 
	 * @return the absolute path of the local working directory.
	 */
	public String lpwd() {
		return lcwd;
	}

	/**
	 * <p>
	 * Download the remote file to the local computer.
	 * </p>
	 * 
	 * @param path
	 *            the path to the remote file
	 * @param progress
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String path, FileTransferProgress progress)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		return get(path, progress, false);
	}

	/**
	 * <p>
	 * Download the remote file to the local computer.
	 * </p>
	 * 
	 * @param path
	 *            the path to the remote file
	 * @param progress
	 * @param resume
	 *            attempt to resume a interrupted download
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String path, FileTransferProgress progress,
			boolean resume) throws FileNotFoundException, SftpStatusException,
			SshException, TransferCancelledException {
		String localfile;

		if (path.lastIndexOf("/") > -1) {
			localfile = path.substring(path.lastIndexOf("/") + 1);
		} else {
			localfile = path;
		}

		return get(path, localfile, progress, resume);
	}

	/**
	 * <p>
	 * Download the remote file to the local computer
	 * 
	 * @param path
	 *            the path to the remote file
	 * @param resume
	 *            attempt to resume an interrupted download
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String path, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		return get(path, (FileTransferProgress) null, resume);
	}

	/**
	 * <p>
	 * Download the remote file to the local computer
	 * 
	 * @param path
	 *            the path to the remote file
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String path) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		return get(path, (FileTransferProgress) null);
	}

	// private void transferFile(InputStream in, OutputStream out,
	// FileTransferProgress progress) throws SftpStatusException,
	// SshException, TransferCancelledException {
	// try {
	// long bytesSoFar = 0;
	// byte[] buffer = new byte[blocksize];
	// int read;
	//
	// while ((read = in.read(buffer)) > -1) {
	// if ((progress != null) && progress.isCancelled()) {
	// throw new TransferCancelledException();
	// }
	//
	// if (read > 0) {
	// out.write(buffer, 0, read);
	// // out.flush();
	// bytesSoFar += read;
	//
	// if (progress != null) {
	// progress.progressed(bytesSoFar);
	// }
	// }
	// }
	// } catch (IOException ex) {
	// throw new SftpStatusException(SftpStatusException.SSH_FX_FAILURE,
	// "IO Error during data transfer: " + ex.getMessage());
	// } finally {
	// try {
	// in.close();
	// } catch (Throwable t) {
	// }
	//
	// try {
	// out.close();
	// } catch (Throwable ex) {
	// }
	// }
	// }

	/**
	 * <p>
	 * Download the remote file to the local computer. If the paths provided are
	 * not absolute the current working directory is used.
	 * </p>
	 * 
	 * @param remote
	 *            the path/name of the remote file
	 * @param local
	 *            the path/name to place the file on the local computer
	 * @param progress
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws SftpStatusException
	 * @throws FileNotFoundException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String remote, String local,
			FileTransferProgress progress) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		return get(remote, local, progress, false);
	}

	/**
	 * <p>
	 * Download the remote file to the local computer. If the paths provided are
	 * not absolute the current working directory is used.
	 * </p>
	 * 
	 * @param remote
	 *            the path/name of the remote file
	 * @param local
	 *            the path/name to place the file on the local computer
	 * @param progress
	 * @param resume
	 *            attempt to resume an interrupted download
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws SftpStatusException
	 * @throws FileNotFoundException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	@SuppressWarnings("resource")
	public SftpFileAttributes get(String remote, String local,
			FileTransferProgress progress, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {

		// Moved here to ensure that stream is closed in finally
		OutputStream out = null;
		SftpFileAttributes attrs = null;

		// Perform local file operations first, then if it throws an exception
		// the server hasn't been unnecessarily loaded.
		File localPath = resolveLocalPath(local);
		if (!localPath.exists()) {
			File parent = new File(localPath.getParent());
			parent.mkdirs();
		}

		if (localPath.isDirectory()) {
			int idx;
			if ((idx = remote.lastIndexOf('/')) > -1) {
				localPath = new File(localPath, remote.substring(idx));
			} else {
				localPath = new File(localPath, remote);
			}

		}

		// Check that file exists before we create a file
		stat(remote);

		long position = 0;

		try {

			// if resuming and the local file exists, then open as random access
			// file and seek to end of the file ready to continue writing
			if (resume && localPath.exists()) {

				position = localPath.length();

				RandomAccessFile file = new RandomAccessFile(localPath, "rw");
				file.seek(position);

				out = new RandomAccessFileOutputStream(file);
			} else {
				out = new FileOutputStream(localPath);
			}

			if (transferMode == MODE_TEXT) {

				// Default text mode handling for versions 3- of the SFTP
				// protocol
				int inputStyle = eolMode;
				int outputStyle = EOLProcessor.TEXT_SYSTEM;

				byte[] nl = null;

				if (sftp.getVersion() <= 3
						&& sftp.getExtension("newline@vandyke.com") != null) {
					nl = sftp.getExtension("newline@vandyke.com");
				} else if (sftp.getVersion() > 3) {
					nl = sftp.getCanonicalNewline();
				}

				// Setup text mode correctly if were using version 4+ of the
				// SFTP protocol
				if (nl != null) {
					switch (nl.length) {
					case 1:
						if (nl[0] == '\r')
							inputStyle = EOLProcessor.TEXT_CR;
						else if (nl[0] == '\n')
							inputStyle = EOLProcessor.TEXT_LF;
						else
							throw new SftpStatusException(
									SftpStatusException.INVALID_HANDLE,
									"Unsupported text mode: invalid newline character");
						break;
					case 2:
						if (nl[0] == '\r' && nl[1] == '\n')
							inputStyle = EOLProcessor.TEXT_CRLF;
						else
							throw new SftpStatusException(
									SftpStatusException.INVALID_HANDLE,
									"Unsupported text mode: invalid newline characters");
						break;
					default:
						throw new SftpStatusException(
								SftpStatusException.INVALID_HANDLE,
								"Unsupported text mode: newline length > 2");

					}

				}

				out = EOLProcessor.createOutputStream(inputStyle, outputStyle,
						out);
			}

			attrs = get(remote, out, progress, position);

			return attrs;

		} catch (IOException ex) {
			throw new SftpStatusException(SftpStatusException.SSH_FX_FAILURE,
					"Failed to open outputstream to " + local);
		} finally {
			try {
				if (out != null)
					out.close();

				// Try to set the last modified time on file using reflection so
				// that
				// the class is compatible with JDK 1.1
				if (attrs != null) {
					Method m = localPath.getClass().getMethod(
							"setLastModified", new Class[] { long.class });
					m.invoke(localPath, new Object[] { new Long(attrs
							.getModifiedTime().longValue() * 1000) });
				}

			} catch (Throwable ex) {
				// NOTE: should we ignore this?
			}
		}
	}

	/**
	 * Download the remote file into the local file.
	 * 
	 * @param remote
	 * @param local
	 * @param resume
	 *            attempt to resume an interrupted download
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String remote, String local, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		return get(remote, local, null, resume);
	}

	/**
	 * Download the remote file into the local file.
	 * 
	 * @param remote
	 * @param local
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String remote, String local)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		return get(remote, local, false);
	}

	/**
	 * <p>
	 * Download the remote file writing it to the specified
	 * <code>OutputStream</code>. The OutputStream is closed by this method even
	 * if the operation fails.
	 * </p>
	 * 
	 * @param remote
	 *            the path/name of the remote file
	 * @param local
	 *            the OutputStream to write
	 * @param progress
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String remote, OutputStream local,
			FileTransferProgress progress) throws SftpStatusException,
			SshException, TransferCancelledException {
		return get(remote, local, progress, 0);
	}

	/**
	 * make local copies of some of the variables, then call getfilematches,
	 * which calls get on each file that matches the regexp remote.
	 * 
	 * @param remote
	 * @param local
	 * @param progress
	 * @param position
	 * @return SftpFile[]
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	/*
	 * public SftpFile[] getFiles(String remote, OutputStream local,
	 * FileTransferProgress progress, long position) throws
	 * FileNotFoundException, SftpStatusException, SshException,
	 * TransferCancelledException { this.local = local; this.position =
	 * position; return getFileMatches(remote, progress, true); }
	 */
	/**
	 * make local copies of some of the variables, then call getfilematches,
	 * which calls get on each file that matches the regexp remote.
	 * 
	 * @param remote
	 * @param local
	 * @param progress
	 * @param resume
	 * @return SftpFile[]
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	/*
	 * public SftpFile[] getFiles(String remote, String local,
	 * FileTransferProgress progress) throws FileNotFoundException,
	 * SftpStatusException, SshException, TransferCancelledException { return
	 * getFileMatches(remote, progress); }
	 */

	/**
	 * constants for setting the regular expression syntax.
	 */
	public static final int NoSyntax = 0;
	public static final int GlobSyntax = 1;
	public static final int Perl5Syntax = 2;

	/**
	 * default regular expression syntax is to not perform regular expression
	 * matching on getFiles() and putFiles()
	 */
	private int RegExpSyntax = GlobSyntax;

	/**
	 * sets the type of regular expression matching to perform on gets and puts
	 * 
	 * @param syntax
	 *            , NoSyntax for no regular expression matching, GlobSyntax for
	 *            GlobSyntax, Perl5Syntax for Perl5Syntax
	 */
	public void setRegularExpressionSyntax(int syntax) {
		RegExpSyntax = syntax;
	}

	/**
	 * Called by getFileMatches() to do regular expression pattern matching on
	 * the files in 'remote''s parent directory.
	 * 
	 * @param remote
	 * @return SftpFile[]
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public SftpFile[] matchRemoteFiles(String remote)
			throws SftpStatusException, SshException {

		String actualDir;
		String actualSearch;
		int fileSeparatorIndex;
		if ((fileSeparatorIndex = remote.lastIndexOf("/")) > -1) {
			actualDir = remote.substring(0, fileSeparatorIndex);
			actualSearch = remote.length() > fileSeparatorIndex + 1 ? remote
					.substring(fileSeparatorIndex + 1) : "";
		} else {
			actualDir = cwd;
			actualSearch = remote;
		}

		SftpFile[] files;
		RegularExpressionMatching matcher;
		switch (RegExpSyntax) {
		case GlobSyntax:
			matcher = new GlobRegExpMatching();
			files = ls(actualDir);
			break;
		case Perl5Syntax:
			matcher = new Perl5RegExpMatching();
			files = ls(actualDir);
			break;
		default:
			matcher = new NoRegExpMatching();
			files = new SftpFile[1];
			String actual = resolveRemotePath(remote);
			files[0] = getSubsystemChannel().getFile(actual);
		}

		return matcher.matchFilesWithPattern(files, actualSearch);
	}

	/**
	 * If RegExpSyntax is set to GlobSyntax or Perl5Syntax then it pattern
	 * matches the files in the remote directory using "remote" as a glob or
	 * perl5 Regular Expression. For each matching file get() is called to copy
	 * the file to the local directory.
	 * 
	 * <p>
	 * If RegExpSyntax is set to NoSyntax then "remote" is treated as a filepath
	 * instead of a regular expression
	 * </p>
	 * 
	 * @param remote
	 * @param progress
	 * @param streamOrFile
	 * @return SftpFile[] of SftpFile's that have been retrieved
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	private SftpFile[] getFileMatches(String remote, String local,
			FileTransferProgress progress, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {

		// match with files using remote as regular expression.
		SftpFile[] matchedFiles = matchRemoteFiles(remote);

		Vector<SftpFile> retrievedFiles = new Vector<SftpFile>();
		// call get for each matched file, append the files attributes to a
		// vector to be returned at the end
		// call the correct get method depending on the get method that called
		// this
		for (int i = 0; i < matchedFiles.length; i++) {
			get(matchedFiles[i].getAbsolutePath(), local, progress, resume);
			retrievedFiles.addElement(matchedFiles[i]);
		}

		// return (SftpFile[]) retrievedFiles.toArray(new SftpFile[0]);
		SftpFile[] retrievedSftpFiles = new SftpFile[retrievedFiles.size()];
		retrievedFiles.copyInto(retrievedSftpFiles);
		return retrievedSftpFiles;
	}

	/**
	 * Called by putFileMatches() to do regular expression pattern matching on
	 * the files in 'local''s parent directory.
	 * 
	 * @param local
	 * @return String[]
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	private String[] matchLocalFiles(String local) throws SftpStatusException,
			SshException {

		// Resolve the search path as it may not be CWD
		String actualDir;
		String actualSearch;
		int fileSeparatorIndex;
		if ((fileSeparatorIndex = local.lastIndexOf(System
				.getProperty("file.separator"))) > -1
				|| (fileSeparatorIndex = local.lastIndexOf('/')) > -1) {
			actualDir = resolveLocalPath(local.substring(0, fileSeparatorIndex))
					.getAbsolutePath();
			actualSearch = (fileSeparatorIndex < local.length() - 1) ? local
					.substring(fileSeparatorIndex + 1) : "";
		} else {
			actualDir = lcwd;
			actualSearch = local;
		}

		File f;
		RegularExpressionMatching matcher;
		File[] files;
		switch (RegExpSyntax) {
		case GlobSyntax:
			f = new File(actualDir);
			matcher = new GlobRegExpMatching();
			files = listFiles(f);
			break;
		case Perl5Syntax:
			f = new File(actualDir);
			matcher = new Perl5RegExpMatching();
			files = listFiles(f);
			break;
		default:
			matcher = new NoRegExpMatching();
			files = new File[1];
			files[0] = new File(local);
		}

		return matcher.matchFileNamesWithPattern(files, actualSearch);
	}

	private File[] listFiles(File f) {
		String parentDir = f.getAbsolutePath();
		String[] fileNames = f.list();
		File[] files = new File[fileNames.length];
		for (int i = 0; i < fileNames.length; i++) {
			files[i] = new File(parentDir, fileNames[i]);
		}
		return files;
	}

	/**
	 * If RegExpSyntax is set to GlobSyntax or Perl5Syntax then it pattern
	 * matches the files in the local directory using "local" as a glob or perl5
	 * Regular Expression. For each matching file put() is called to copy the
	 * file to the remote directory.
	 * 
	 * <p>
	 * If RegExpSyntax is set to NoSyntax then "local" is treated as a filepath
	 * instead of a regular expression.
	 * </p>
	 * 
	 * @param local
	 * @param progress
	 * @param streamOrFile
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	private void putFileMatches(String local, String remote,
			FileTransferProgress progress, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {

		String remotePath = resolveRemotePath(remote);
		// Remote must be a valid remote directory

		SftpFileAttributes attrs = null;
		try {
			attrs = stat(remotePath);
		} catch (SftpStatusException ex) {
			throw new SftpStatusException(
					ex.getStatus(),
					"Remote path '"
							+ remote
							+ "' does not exist. It must be a valid directory and must already exist!");
		}

		if (!attrs.isDirectory())
			throw new SftpStatusException(
					SftpStatusException.SSH_FX_NO_SUCH_PATH, "Remote path '"
							+ remote + "' is not a directory!");

		String[] matchedFiles = matchLocalFiles(local);

		// call put for each matched file
		// call the correct put method depending on the put method that called
		// this

		for (int i = 0; i < matchedFiles.length; i++) {
			// use file exists once added rather than try catch
			try {
				put(matchedFiles[i], remotePath, progress, resume);
			} catch (SftpStatusException ex) {
				throw new SftpStatusException(ex.getStatus(), "Failed to put "
						+ matchedFiles[i] + " to " + remote + " ["
						+ ex.getMessage() + "]");
			}
		}
	}

	/**
	 * <p>
	 * Download the remote file writing it to the specified
	 * <code>OutputStream</code>. The OutputStream is closed by this method even
	 * if the operation fails.
	 * </p>
	 * 
	 * @param remote
	 *            the path/name of the remote file
	 * @param local
	 *            the OutputStream to write
	 * @param progress
	 * @param position
	 *            the position within the file to start reading from
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String remote, OutputStream local,
			FileTransferProgress progress, long position)
			throws SftpStatusException, SshException,
			TransferCancelledException {

		String remotePath = resolveRemotePath(remote);
		SftpFileAttributes attrs = sftp.getAttributes(remotePath);

		if (position > attrs.getSize().longValue()) {
			throw new SftpStatusException(
					SftpStatusException.INVALID_RESUME_STATE,
					"The local file size is greater than the remote file");
		}

		if (progress != null) {
			progress.started(attrs.getSize().longValue() - position, remotePath);
		}

		SftpFile file;

		if (transferMode == MODE_TEXT && sftp.getVersion() > 3) {
			file = sftp.openFile(remotePath, SftpSubsystemChannel.OPEN_READ
					| SftpSubsystemChannel.OPEN_TEXT);

		} else {
			file = sftp.openFile(remotePath, SftpSubsystemChannel.OPEN_READ);

		}

		try {
			sftp.performOptimizedRead(file.getHandle(), attrs.getSize()
					.longValue(), blocksize, local, asyncRequests, progress,
					position);
		} catch (TransferCancelledException tce) {
			throw tce;
		} finally {

			try {
				local.close();
			} catch (Throwable t) {
			}
			try {
				sftp.closeFile(file);
			} catch (SftpStatusException ex) {
			}
		}

		if (progress != null) {
			progress.completed();
		}

		return attrs;
	}

	/**
	 * Create an InputStream for reading a remote file.
	 * 
	 * @param remotefile
	 * @param position
	 * @return InputStream
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public InputStream getInputStream(String remotefile, long position)
			throws SftpStatusException, SshException {
		String remotePath = resolveRemotePath(remotefile);
		sftp.getAttributes(remotePath);

		return new SftpFileInputStream(sftp.openFile(remotePath,
				SftpSubsystemChannel.OPEN_READ), position);

	}

	/**
	 * Create an InputStream for reading a remote file.
	 * 
	 * @param remotefile
	 * @return InputStream
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public InputStream getInputStream(String remotefile)
			throws SftpStatusException, SshException {
		return getInputStream(remotefile, 0);
	}

	/**
	 * Download the remote file into an OutputStream.
	 * 
	 * @param remote
	 * @param local
	 * @param position
	 *            the position from which to start reading the remote file
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String remote, OutputStream local,
			long position) throws SftpStatusException, SshException,
			TransferCancelledException {
		return get(remote, local, null, position);
	}

	/**
	 * Download the remote file into an OutputStream.
	 * 
	 * @param remote
	 * @param local
	 * 
	 * @return the downloaded file's attributes
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFileAttributes get(String remote, OutputStream local)
			throws SftpStatusException, SshException,
			TransferCancelledException {
		return get(remote, local, null, 0);
	}

	/**
	 * <p>
	 * Returns the state of the SFTP client. The client is closed if the
	 * underlying session channel is closed. Invoking the <code>quit</code>
	 * method of this object will close the underlying session channel.
	 * </p>
	 * 
	 * @return true if the client is still connected, otherwise false
	 */
	public boolean isClosed() {
		return sftp.isClosed();
	}

	/**
	 * <p>
	 * Upload a file to the remote computer.
	 * </p>
	 * 
	 * @param local
	 *            the path/name of the local file
	 * @param progress
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void put(String local, FileTransferProgress progress, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		File f = new File(local);
		put(local, f.getName(), progress, resume);
	}

	/**
	 * <p>
	 * Upload a file to the remote computer.
	 * </p>
	 * 
	 * @param local
	 *            the path/name of the local file
	 * @param progress
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void put(String local, FileTransferProgress progress)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		put(local, progress, false);
	}

	/**
	 * Upload a file to the remote computer
	 * 
	 * @param local
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void put(String local) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		put(local, false);
	}

	/**
	 * Upload a file to the remote computer
	 * 
	 * @param local
	 * @param resume
	 *            attempt to resume after an interrupted transfer
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void put(String local, boolean resume) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		put(local, (FileTransferProgress) null, resume);
	}

	/**
	 * <p>
	 * Upload a file to the remote computer. If the paths provided are not
	 * absolute the current working directory is used.
	 * </p>
	 * 
	 * @param local
	 *            the path/name of the local file
	 * @param remote
	 *            the path/name of the destination file
	 * @param progress
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void put(String local, String remote, FileTransferProgress progress)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		put(local, remote, progress, false);
	}

	/**
	 * <p>
	 * Upload a file to the remote computer. If the paths provided are not
	 * absolute the current working directory is used.
	 * </p>
	 * 
	 * @param local
	 *            the path/name of the local file
	 * @param remote
	 *            the path/name of the destination file
	 * @param progress
	 * @param resume
	 *            attempt to resume after an interrupted transfer
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void put(String local, String remote, FileTransferProgress progress,
			boolean resume) throws FileNotFoundException, SftpStatusException,
			SshException, TransferCancelledException {
		File localPath = resolveLocalPath(local);

		InputStream in = new FileInputStream(localPath);
		// File f = new File(local);
		long position = 0;

		try {
			SftpFileAttributes attrs = stat(remote);
			if (attrs.isDirectory()) {
				remote += (remote.endsWith("/") ? "" : "/")
						+ localPath.getName();
				attrs = stat(remote);
			}

			if (resume) {
				if (localPath.length() <= attrs.getSize().longValue()) {
					try {
						in.close();
					} catch (IOException e) {
					}
					throw new SftpStatusException(
							SftpStatusException.INVALID_RESUME_STATE,
							"The remote file size is greater than the local file");
				}
				try {
					position = attrs.getSize().longValue();
					in.skip(position);
				} catch (IOException ex) {
					try {
						in.close();
					} catch (IOException e) {
					}
					throw new SftpStatusException(
							SftpStatusException.SSH_FX_NO_SUCH_FILE,
							ex.getMessage());
				}

			}
		} catch (SftpStatusException ex) {
			// file didnt exist so dont need to do above
		}

		put(in, remote, progress, position);

	}

	/**
	 * Upload a file to the remote computer
	 * 
	 * @param local
	 * @param remote
	 * @param resume
	 *            attempt to resume after an interrupted transfer
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void put(String local, String remote, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		put(local, remote, null, resume);
	}

	/**
	 * Upload a file to the remote computer
	 * 
	 * @param local
	 * @param remote
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void put(String local, String remote) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		put(local, remote, null, false);
	}

	/**
	 * <p>
	 * Upload a file to the remote computer reading from the specified <code>
	 * InputStream</code>. The InputStream is closed, even if the operation
	 * fails.
	 * </p>
	 * 
	 * @param in
	 *            the InputStream being read
	 * @param remote
	 *            the path/name of the destination file
	 * @param progress
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public void put(InputStream in, String remote, FileTransferProgress progress)
			throws SftpStatusException, SshException,
			TransferCancelledException {
		put(in, remote, progress, 0);
	}

	public void put(InputStream in, String remote,
			FileTransferProgress progress, long position)
			throws SftpStatusException, SshException,
			TransferCancelledException {
		String remotePath = resolveRemotePath(remote);

		SftpFileAttributes attrs = null;

		SftpFile file;

		if (transferMode == MODE_TEXT) {

			// Default text mode handling for versions 3- of the SFTP protocol
			int inputStyle = EOLProcessor.TEXT_SYSTEM;
			int outputStyle = eolMode;

			byte[] nl = null;

			if (sftp.getVersion() <= 3
					&& sftp.getExtension("newline@vandyke.com") != null) {
				nl = sftp.getExtension("newline@vandyke.com");
			} else if (sftp.getVersion() > 3) {
				nl = sftp.getCanonicalNewline();
			}
			// Setup text mode correctly if were using version 4+ of the
			// SFTP protocol
			if (nl != null) {
				switch (nl.length) {
				case 1:
					if (nl[0] == '\r')
						outputStyle = EOLProcessor.TEXT_CR;
					else if (nl[0] == '\n')
						outputStyle = EOLProcessor.TEXT_LF;
					else
						throw new SftpStatusException(
								SftpStatusException.INVALID_HANDLE,
								"Unsupported text mode: invalid newline character");
					break;
				case 2:
					if (nl[0] == '\r' && nl[1] == '\n')
						outputStyle = EOLProcessor.TEXT_CRLF;
					else
						throw new SftpStatusException(
								SftpStatusException.INVALID_HANDLE,
								"Unsupported text mode: invalid newline characters");
					break;
				default:
					throw new SftpStatusException(
							SftpStatusException.INVALID_HANDLE,
							"Unsupported text mode: newline length > 2");

				}
			}

			try {
				in = EOLProcessor
						.createInputStream(inputStyle, outputStyle, in);
			} catch (IOException ex) {
				throw new SshException(
						"Failed to create EOL processing stream",
						SshException.INTERNAL_ERROR);
			}
		}

		attrs = new SftpFileAttributes(sftp,
				SftpFileAttributes.SSH_FILEXFER_TYPE_REGULAR);

		attrs.setPermissions(new UnsignedInteger32(0666 ^ umask));

		if (position > 0) {

			if (transferMode == MODE_TEXT && sftp.getVersion() > 3) {
				throw new SftpStatusException(
						SftpStatusException.SSH_FX_OP_UNSUPPORTED,
						"Resume on text mode files is not supported");
			}

			file = sftp.openFile(remotePath, SftpSubsystemChannel.OPEN_APPEND
					| SftpSubsystemChannel.OPEN_WRITE, attrs);
		} else {
			if (transferMode == MODE_TEXT && sftp.getVersion() > 3) {
				file = sftp.openFile(remotePath,
						SftpSubsystemChannel.OPEN_CREATE
								| SftpSubsystemChannel.OPEN_TRUNCATE
								| SftpSubsystemChannel.OPEN_WRITE
								| SftpSubsystemChannel.OPEN_TEXT, attrs);
			} else {
				file = sftp.openFile(remotePath,
						SftpSubsystemChannel.OPEN_CREATE
								| SftpSubsystemChannel.OPEN_TRUNCATE
								| SftpSubsystemChannel.OPEN_WRITE, attrs);
			}
		}

		if (progress != null) {
			try {
				progress.started(in.available(), remotePath);
			} catch (IOException ex1) {
				throw new SshException("Failed to determine local file size",
						SshException.INTERNAL_ERROR);
			}
		}

		try {
			sftp.performOptimizedWrite(file.getHandle(), blocksize,
					asyncRequests, in, buffersize, progress, position);
		} finally {
			try {
				in.close();
			} catch (Throwable t) {
			}
			sftp.closeFile(file);
		}

		if (progress != null) {
			progress.completed();
		}

	}

	/**
	 * Create an OutputStream for writing to a remote file.
	 * 
	 * @param remotefile
	 * @return OutputStream
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public OutputStream getOutputStream(String remotefile)
			throws SftpStatusException, SshException {

		String remotePath = resolveRemotePath(remotefile);
		return new SftpFileOutputStream(sftp.openFile(remotePath,
				SftpSubsystemChannel.OPEN_CREATE
						| SftpSubsystemChannel.OPEN_TRUNCATE
						| SftpSubsystemChannel.OPEN_WRITE));

	}

	/**
	 * Upload the contents of an InputStream to the remote computer.
	 * 
	 * @param in
	 * @param remote
	 * @param position
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public void put(InputStream in, String remote, long position)
			throws SftpStatusException, SshException,
			TransferCancelledException {
		put(in, remote, null, position);
	}

	/**
	 * Upload the contents of an InputStream to the remote computer.
	 * 
	 * @param in
	 * @param remote
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public void put(InputStream in, String remote) throws SftpStatusException,
			SshException, TransferCancelledException {
		put(in, remote, null, 0);
	}

	/**
	 * <p>
	 * Sets the user ID to owner for the file or directory.
	 * </p>
	 * 
	 * @param uid
	 *            numeric user id of the new owner
	 * @param path
	 *            the path to the remote file/directory
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * 
	 */
	public void chown(String uid, String path) throws SftpStatusException,
			SshException {
		String actual = resolveRemotePath(path);

		SftpFileAttributes attrs = sftp.getAttributes(actual);
		attrs.setUID(uid);
		sftp.setAttributes(actual, attrs);

	}

	/**
	 * <p>
	 * Sets the group ID for the file or directory.
	 * </p>
	 * 
	 * @param gid
	 *            the numeric group id for the new group
	 * @param path
	 *            the path to the remote file/directory
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public void chgrp(String gid, String path) throws SftpStatusException,
			SshException {
		String actual = resolveRemotePath(path);

		SftpFileAttributes attrs = sftp.getAttributes(actual);
		attrs.setGID(gid);
		sftp.setAttributes(actual, attrs);

	}

	/**
	 * <p>
	 * Changes the access permissions or modes of the specified file or
	 * directory.
	 * </p>
	 * 
	 * <p>
	 * Modes determine who can read, change or execute a file.
	 * </p>
	 * <blockquote>
	 * 
	 * <pre>
	 * Absolute modes are octal numbers specifying the complete list of
	 * attributes for the files; you specify attributes by OR'ing together
	 * these bits.
	 * 
	 * 0400       Individual read
	 * 0200       Individual write
	 * 0100       Individual execute (or list directory)
	 * 0040       Group read
	 * 0020       Group write
	 * 0010       Group execute
	 * 0004       Other read
	 * 0002       Other write
	 * 0001       Other execute
	 * </pre>
	 * 
	 * </blockquote>
	 * 
	 * @param permissions
	 *            the absolute mode of the file/directory
	 * @param path
	 *            the path to the file/directory on the remote server
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public void chmod(int permissions, String path) throws SftpStatusException,
			SshException {
		String actual = resolveRemotePath(path);
		sftp.changePermissions(actual, permissions);
	}

	/**
	 * Sets the umask for this client.<br>
	 * <blockquote>
	 * 
	 * <pre>
	 * To give yourself full permissions for both files and directories and
	 * prevent the group and other users from having access:
	 * 
	 *   umask(&quot;077&quot;);
	 * 
	 * This subtracts 077 from the system defaults for files and directories
	 * 666 and 777. Giving a default access permissions for your files of
	 * 600 (rw-------) and for directories of 700 (rwx------).
	 * 
	 * To give all access permissions to the group and allow other users read
	 * and execute permission:
	 * 
	 *   umask(&quot;002&quot;);
	 * 
	 * This subtracts 002 from the system defaults to give a default access permission
	 * for your files of 664 (rw-rw-r--) and for your directories of 775 (rwxrwxr-x).
	 * 
	 * To give the group and other users all access except write access:
	 * 
	 *   umask(&quot;022&quot;);
	 * 
	 * This subtracts 022 from the system defaults to give a default access permission
	 * for your files of 644 (rw-r--r--) and for your directories of 755 (rwxr-xr-x).
	 * </pre>
	 * 
	 * </blockquote>
	 * 
	 * @param umask
	 * @throws SshException
	 */
	public void umask(String umask) throws SshException {
		try {
			this.umask = Integer.parseInt(umask, 8);
		} catch (NumberFormatException ex) {
			throw new SshException(
					"umask must be 4 digit octal number e.g. 0022",
					SshException.BAD_API_USAGE);
		}
	}

	/**
	 * <p>
	 * Rename a file on the remote computer.
	 * </p>
	 * 
	 * @param oldpath
	 *            the old path
	 * @param newpath
	 *            the new path
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public void rename(String oldpath, String newpath)
			throws SftpStatusException, SshException {
		String from = resolveRemotePath(oldpath);
		String to = resolveRemotePath(newpath);

		SftpFileAttributes attrs = null;

		try {
			attrs = sftp.getAttributes(to);

		} catch (SftpStatusException ex) {
			sftp.renameFile(from, to);
			return;
		}

		if (attrs != null && attrs.isDirectory()) {
			sftp.renameFile(from, to);
		} else {
			throw new SftpStatusException(
					SftpStatusException.SSH_FX_FILE_ALREADY_EXISTS, newpath
							+ " already exists on the remote filesystem");
		}

	}

	/**
	 * <p>
	 * Remove a file or directory from the remote computer.
	 * </p>
	 * 
	 * @param path
	 *            the path of the remote file/directory
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public void rm(String path) throws SftpStatusException, SshException {
		String actual = resolveRemotePath(path);

		SftpFileAttributes attrs = sftp.getAttributes(actual);
		if (attrs.isDirectory()) {
			sftp.removeDirectory(actual);
		} else {
			sftp.removeFile(actual);
		}
	}

	/**
	 * Remove a file or directory on the remote computer with options to force
	 * deletion of existing files and recursion.
	 * 
	 * @param path
	 * @param force
	 * @param recurse
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public void rm(String path, boolean force, boolean recurse)
			throws SftpStatusException, SshException {
		String actual = resolveRemotePath(path);

		SftpFileAttributes attrs = null;

		attrs = sftp.getAttributes(actual);

		SftpFile file;

		if (attrs.isDirectory()) {
			SftpFile[] list = ls(path);

			if (!force && (list.length > 0)) {
				throw new SftpStatusException(
						SftpStatusException.SSH_FX_FAILURE,
						"You cannot delete non-empty directory, use force=true to overide");
			}
			for (int i = 0; i < list.length; i++) {
				file = list[i];

				if (file.isDirectory() && !file.getFilename().equals(".")
						&& !file.getFilename().equals("..")) {
					if (recurse) {
						rm(file.getAbsolutePath(), force, recurse);
					} else {
						throw new SftpStatusException(
								SftpStatusException.SSH_FX_FAILURE,
								"Directory has contents, cannot delete without recurse=true");
					}
				} else if (file.isFile()) {
					sftp.removeFile(file.getAbsolutePath());
				}
			}

			sftp.removeDirectory(actual);
		} else {
			sftp.removeFile(actual);
		}
	}

	/**
	 * <p>
	 * Create a symbolic link on the remote computer.
	 * </p>
	 * 
	 * @param path
	 *            the path to the existing file
	 * @param link
	 *            the new link
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public void symlink(String path, String link) throws SftpStatusException,
			SshException {
		String actualPath = resolveRemotePath(path);
		String actualLink = resolveRemotePath(link);

		sftp.createSymbolicLink(actualLink, actualPath);
	}

	/**
	 * <p>
	 * Returns the attributes of the file from the remote computer.
	 * </p>
	 * 
	 * @param path
	 *            the path of the file on the remote computer
	 * 
	 * @return the attributes
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public SftpFileAttributes stat(String path) throws SftpStatusException,
			SshException {
		String actual = resolveRemotePath(path);
		return sftp.getAttributes(actual);
	}

	/**
	 * <p>
	 * Returns the attributes of a link on the remote computer.
	 * </p>
	 * 
	 * @param path
	 *            the path of the file on the remote computer
	 * 
	 * @return the attributes
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public SftpFileAttributes statLink(String path) throws SftpStatusException,
			SshException {
		String actual = resolveRemotePath(path);
		return sftp.getLinkAttributes(actual);
	}

	/**
	 * Get the absolute path for a file.
	 * 
	 * @param path
	 * 
	 * @return String
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 */
	public String getAbsolutePath(String path) throws SftpStatusException,
			SshException {
		String actual = resolveRemotePath(path);
		return sftp.getAbsolutePath(actual);
	}

	/**
	 * <p>
	 * Close the SFTP client.
	 * </p>
	 * 
	 */
	public void quit() throws SshException {
		try {
			sftp.close();
		} catch (SshIOException ex) {
			throw ex.getRealException();
		} catch (IOException ex1) {
			throw new SshException(ex1.getMessage(),
					SshException.CHANNEL_FAILURE);
		}

	}

	/**
	 * <p>
	 * Close the SFTP client.
	 * </p>
	 * 
	 */
	public void exit() throws SshException {
		try {
			sftp.close();
		} catch (SshIOException ex) {
			throw ex.getRealException();
		} catch (IOException ex1) {
			throw new SshException(ex1.getMessage(),
					SshException.CHANNEL_FAILURE);
		}

	}

	/**
	 * Copy the contents of a local directory into a remote directory.
	 * 
	 * @param localdir
	 *            the path to the local directory
	 * @param remotedir
	 *            the remote directory which will receive the contents
	 * @param recurse
	 *            recurse through child folders
	 * @param sync
	 *            synchronize the directories by removing files on the remote
	 *            server that do not exist locally
	 * @param commit
	 *            actually perform the operation. If <tt>false</tt> a <a
	 *            href="DirectoryOperation.html">DirectoryOperation</a> will be
	 *            returned so that the operation can be evaluated and no actual
	 *            files will be created/transfered.
	 * @param progress
	 * 
	 * @return DirectoryOperation
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public DirectoryOperation copyLocalDirectory(String localdir,
			String remotedir, boolean recurse, boolean sync, boolean commit,
			FileTransferProgress progress) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		DirectoryOperation op = new DirectoryOperation();

		// Record the previous
		// String pwd = pwd();
		// String lpwd = lpwd();

		File local = resolveLocalPath(localdir);

		remotedir = resolveRemotePath(remotedir);
		remotedir += (remotedir.endsWith("/") ? "" : "/");

		// Setup the remote directory if were committing
		if (commit) {
			try {
				sftp.getAttributes(remotedir);
			} catch (SftpStatusException ex) {
				mkdirs(remotedir);
			}
		}

		// List the local files and verify against the remote server
		String[] ls = local.list();
		File source;
		if (ls != null) {
			for (int i = 0; i < ls.length; i++) {
				source = new File(local, ls[i]);
				if (source.isDirectory() && !source.getName().equals(".")
						&& !source.getName().equals("..")) {
					if (recurse) {
						// File f = new File(local, source.getName());
						op.addDirectoryOperation(
								copyLocalDirectory(source.getAbsolutePath(),
										remotedir + source.getName(), recurse,
										sync, commit, progress), source);
					}
				} else if (source.isFile()) {

					boolean newFile = false;
					boolean unchangedFile = false;

					try {
						SftpFileAttributes attrs = sftp.getAttributes(remotedir
								+ source.getName());
						unchangedFile = ((source.length() == attrs.getSize()
								.longValue()) && ((source.lastModified() / 1000) == attrs
								.getModifiedTime().longValue()));

					} catch (SftpStatusException ex) {
						newFile = true;
					}

					try {

						if (commit && !unchangedFile) { // BPS - Added
							// !unChangedFile test.
							// Why would want to
							// copy that has been
							// determined to be
							// unchanged?
							put(source.getAbsolutePath(),
									remotedir + source.getName(), progress);
							SftpFileAttributes attrs = sftp
									.getAttributes(remotedir + source.getName());
							attrs.setTimes(
									new UnsignedInteger64(
											source.lastModified() / 1000),
									new UnsignedInteger64(
											source.lastModified() / 1000));
							sftp.setAttributes(remotedir + source.getName(),
									attrs);
						}

						if (unchangedFile) {
							op.addUnchangedFile(source);
						} else if (!newFile) {
							op.addUpdatedFile(source);
						} else {
							op.addNewFile(source);
						}

					} catch (SftpStatusException ex) {
						op.addFailedTransfer(source, ex);
					}
				}
			}
		}

		if (sync) {
			// List the contents of the new remote directory and remove any
			// files/directories that were not updated
			try {
				SftpFile[] files = ls(remotedir);
				SftpFile file;

				File f;

				for (int i = 0; i < files.length; i++) {
					file = files[i];

					// Create a local file object to test for its existence
					f = new File(local, file.getFilename());

					if (!op.containsFile(f) && !file.getFilename().equals(".")
							&& !file.getFilename().equals("..")) {
						op.addDeletedFile(file);

						if (commit) {
							if (file.isDirectory()) {
								// Recurse through the directory, deleting stuff
								recurseMarkForDeletion(file, op);

								if (commit) {
									rm(file.getAbsolutePath(), true, true);
								}
							} else if (file.isFile()) {
								rm(file.getAbsolutePath());
							}
						}
					}
				}
			} catch (SftpStatusException ex2) {
				// Ignore since if it does not exist we cant delete it
			}
		}

		// Return the operation details
		return op;
	}

	private void recurseMarkForDeletion(SftpFile file, DirectoryOperation op)
			throws SftpStatusException, SshException {
		SftpFile[] list = ls(file.getAbsolutePath());
		op.addDeletedFile(file);

		for (int i = 0; i < list.length; i++) {
			file = list[i];

			if (file.isDirectory() && !file.getFilename().equals(".")
					&& !file.getFilename().equals("..")) {
				recurseMarkForDeletion(file, op);
			} else if (file.isFile()) {
				op.addDeletedFile(file);
			}
		}
	}

	private void recurseMarkForDeletion(File file, DirectoryOperation op)
			throws SftpStatusException, SshException {
		String[] list = file.list();
		op.addDeletedFile(file);

		if (list != null) {
			for (int i = 0; i < list.length; i++) {
				file = new File(list[i]);

				if (file.isDirectory() && !file.getName().equals(".")
						&& !file.getName().equals("..")) {
					recurseMarkForDeletion(file, op);
				} else if (file.isFile()) {
					op.addDeletedFile(file);
				}
			}
		}
	}

	/**
	 * Format a String with the details of the file. <blockquote>
	 * 
	 * <pre>
	 * -rwxr-xr-x   1 mjos     staff      348911 Mar 25 14:29 t-filexfer
	 * </pre>
	 * 
	 * </blockquote>
	 * 
	 * @param file
	 * @throws SftpStatusException
	 * @throws SshException
	 * @return String
	 */
	public static String formatLongname(SftpFile file)
			throws SftpStatusException, SshException {
		return formatLongname(file.getAttributes(), file.getFilename());
	}

	/**
	 * Format a String with the details of the file. <blockquote>
	 * 
	 * <pre>
	 * -rwxr-xr-x   1 mjos     staff      348911 Mar 25 14:29 t-filexfer
	 * </pre>
	 * 
	 * </blockquote>
	 * 
	 * @param attrs
	 * @param filename
	 * @return String
	 */
	public static String formatLongname(SftpFileAttributes attrs,
			String filename) {

		StringBuffer str = new StringBuffer();
		str.append(pad(10 - attrs.getPermissionsString().length())
				+ attrs.getPermissionsString());
		str.append("    1 ");
		str.append(attrs.getUID() + pad(8 - attrs.getUID().length())); // uid
		str.append(" ");
		str.append(attrs.getGID() + pad(8 - attrs.getGID().length())); // gid
		str.append(" ");
		str.append(pad(8 - attrs.getSize().toString().length())
				+ attrs.getSize().toString());
		str.append(" ");
		str.append(pad(12 - getModTimeString(attrs.getModifiedTime()).length())
				+ getModTimeString(attrs.getModifiedTime()));
		str.append(" ");
		str.append(filename);

		return str.toString();
	}

	private static String getModTimeString(UnsignedInteger64 mtime) {
		if (mtime == null) {
			return "";
		}

		SimpleDateFormat df;
		long mt = (mtime.longValue() * 1000L);
		long now = System.currentTimeMillis();

		if ((now - mt) > (6 * 30 * 24 * 60 * 60 * 1000L)) {
			df = new SimpleDateFormat("MMM dd  yyyy");
		} else {
			df = new SimpleDateFormat("MMM dd hh:mm");
		}

		return df.format(new Date(mt));
	}

	private static String pad(int num) {

		StringBuffer strBuf = new StringBuffer("");
		if (num > 0) {
			for (int i = 0; i < num; i++) {
				strBuf.append(" ");
			}
		}

		return strBuf.toString();
	}

	/**
	 * Copy the contents of a remote directory to a local directory
	 * 
	 * @param remotedir
	 *            the remote directory whose contents will be copied.
	 * @param localdir
	 *            the local directory to where the contents will be copied
	 * @param recurse
	 *            recurse into child folders
	 * @param sync
	 *            synchronized the directories by removing files and directories
	 *            that do not exist on the remote server.
	 * @param commit
	 *            actually perform the operation. If <tt>false</tt> the
	 *            operation will be processed and a <a
	 *            href="DirectoryOperation.html">DirectoryOperation</a> will be
	 *            returned without actually transfering any files.
	 * @param progress
	 * 
	 * @return DirectoryOperation
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public DirectoryOperation copyRemoteDirectory(String remotedir,
			String localdir, boolean recurse, boolean sync, boolean commit,
			FileTransferProgress progress) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		// Create an operation object to hold the information
		DirectoryOperation op = new DirectoryOperation();

		// Record the previous working directoies
		String pwd = pwd();
		// String lpwd = lpwd();
		cd(remotedir);

		// Setup the local cwd
		String base = remotedir;

		if (base.endsWith("/"))
			base = base.substring(0, base.length() - 1);

		int idx = base.lastIndexOf('/');

		if (idx != -1) {
			base = base.substring(idx + 1);
		}

		File local = new File(localdir);

		if (!local.isAbsolute()) {
			local = new File(lpwd(), localdir);
		}

		if (!local.exists() && commit) {
			local.mkdir();
		}

		SftpFile[] files = ls();
		SftpFile file;
		File f;

		for (int i = 0; i < files.length; i++) {
			file = files[i];

			if (file.isDirectory() && !file.getFilename().equals(".")
					&& !file.getFilename().equals("..")) {
				if (recurse) {
					f = new File(local, file.getFilename());
					op.addDirectoryOperation(
							copyRemoteDirectory(
									file.getFilename(),
									local.getAbsolutePath() + "/"
											+ file.getFilename(), recurse,
									sync, commit, progress), f);
				}
			} else if (file.isFile()) {
				f = new File(local, file.getFilename());

				if (f.exists()
						&& (f.length() == file.getAttributes().getSize()
								.longValue())
						&& ((f.lastModified() / 1000) == file.getAttributes()
								.getModifiedTime().longValue())) {
					if (commit) {
						op.addUnchangedFile(f);
					} else {
						op.addUnchangedFile(file);
					}

					continue;
				}

				try {

					if (f.exists()) {
						if (commit) {
							op.addUpdatedFile(f);
						} else {
							op.addUpdatedFile(file);
						}
					} else {
						if (commit) {
							op.addNewFile(f);
						} else {
							op.addNewFile(file);
						}
					}

					if (commit) {
						// Get the file
						get(file.getFilename(), f.getAbsolutePath(), progress);
					}

				} catch (SftpStatusException ex) {
					op.addFailedTransfer(f, ex);
				}
			}
		}

		if (sync) {
			// List the contents of the new local directory and remove any
			// files/directories that were not updated
			String[] contents = local.list();
			File f2;
			if (contents != null) {
				for (int i = 0; i < contents.length; i++) {
					f2 = new File(local, contents[i]);
					if (!op.containsFile(f2)) {
						op.addDeletedFile(f2);

						if (f2.isDirectory() && !f2.getName().equals(".")
								&& !f2.getName().equals("..")) {
							recurseMarkForDeletion(f2, op);

							if (commit) {
								IOUtil.recurseDeleteDirectory(f2);
							}
						} else if (commit) {
							f2.delete();
						}
					}
				}
			}
		}

		cd(pwd);

		return op;
	}

	/**
	 * <p>
	 * Download the remote files to the local computer
	 * </p>
	 * 
	 * <p>
	 * When RegExpSyntax is set to NoSyntax the getFiles() methods act
	 * identically to the get() methods except for a different return type.
	 * </p>
	 * 
	 * <p>
	 * When RegExpSyntax is set to GlobSyntax or Perl5Syntax, getFiles() treats
	 * 'remote' as a regular expression, and gets all the files in 'remote''s
	 * parent directory that match the pattern. The default parent directory of
	 * remote is the remote cwd unless 'remote' contains file seperators(/).
	 * </p>
	 * 
	 * <p>
	 * Examples can be found in SftpConnect.java
	 * 
	 * <p>
	 * Code Example: <blockquote>
	 * 
	 * <pre>
	 * // change reg exp syntax from default SftpClient.NoSyntax (no reg exp matching)
	 * // to SftpClient.GlobSyntax
	 * sftp.setRegularExpressionSyntax(SftpClient.GlobSyntax);
	 * // get all .doc files with 'rfc' in their names, in the 'docs/unsorted/' folder
	 * // relative to the remote cwd, and copy them to the local cwd.
	 * sftp.getFiles(&quot;docs/unsorted/*rfc*.doc&quot;);
	 * </pre>
	 * 
	 * </blockquote>
	 * </p>
	 * 
	 * @param remote
	 *            the regular expression path to the remote file
	 * 
	 * @return the downloaded files' attributes
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFile[] getFiles(String remote) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		return getFiles(remote, (FileTransferProgress) null);
	}

	/**
	 * <p>
	 * Download the remote files to the local computer
	 * 
	 * @param remote
	 *            the regular expression path to the remote file
	 * @param resume
	 *            attempt to resume an interrupted download
	 * 
	 * @return the downloaded files' attributes
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFile[] getFiles(String remote, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		return getFiles(remote, (FileTransferProgress) null, resume);
	}

	/**
	 * <p>
	 * Download the remote files to the local computer.
	 * </p>
	 * 
	 * @param remote
	 *            the regular expression path to the remote file
	 * @param progress
	 * 
	 * @return SftpFile[]
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFile[] getFiles(String remote, FileTransferProgress progress)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		return getFiles(remote, progress, false);
	}

	/**
	 * <p>
	 * Download the remote files to the local computer.
	 * </p>
	 * 
	 * @param remote
	 *            the regular expression path to the remote file
	 * @param progress
	 * @param resume
	 *            attempt to resume a interrupted download
	 * 
	 * @return SftpFile[]
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFile[] getFiles(String remote, FileTransferProgress progress,
			boolean resume) throws FileNotFoundException, SftpStatusException,
			SshException, TransferCancelledException {
		return getFiles(remote, lcwd, progress, resume);
	}

	/**
	 * Download the remote files into an OutputStream.
	 * 
	 * @param remote
	 * @param local
	 * 
	 * @return SftpFile[]
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	/*
	 * public SftpFile[] getFiles(String remote, OutputStream local) throws
	 * FileNotFoundException, SftpStatusException, SshException,
	 * TransferCancelledException { return getFiles(remote, local, null, 0); }
	 */

	/**
	 * <p>
	 * Download the remote files writing it to the specified
	 * <code>OutputStream</code>. The OutputStream is closed by this mehtod even
	 * if the operation fails.
	 * </p>
	 * 
	 * @param remote
	 *            the regular expression path/name of the remote file
	 * @param local
	 *            the OutputStream to write
	 * @param progress
	 * 
	 * @return SftpFile[]
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	/*
	 * public SftpFile[] getFiles(String remote, OutputStream local,
	 * FileTransferProgress progress) throws FileNotFoundException,
	 * SftpStatusException, SshException, TransferCancelledException { return
	 * getFiles(remote, local, progress, 0); }
	 */

	/**
	 * Download the remote files into an OutputStream.
	 * 
	 * @param remote
	 * @param local
	 * @param position
	 *            the position from which to start reading the remote file
	 * 
	 * @return SftpFile[]
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	/*
	 * public SftpFile[] getFiles(String remote, OutputStream local, long
	 * position) throws FileNotFoundException, SftpStatusException,
	 * SshException, TransferCancelledException { return getFiles(remote, local,
	 * null, position); }
	 */

	/**
	 * Download the remote files into the local file.
	 * 
	 * @param remote
	 * @param local
	 * 
	 * @return SftpFile[]
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFile[] getFiles(String remote, String local)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		return getFiles(remote, local, false);
	}

	/**
	 * Download the remote files into the local file.
	 * 
	 * @param remote
	 * @param local
	 * @param resume
	 *            attempt to resume an interrupted download
	 * 
	 * @return SftpFile[]
	 * 
	 * @throws FileNotFoundException
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFile[] getFiles(String remote, String local, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		return getFiles(remote, local, null, resume);
	}

	/**
	 * <p>
	 * Download the remote file to the local computer. If the paths provided are
	 * not absolute the current working directory is used.
	 * </p>
	 * 
	 * @param remote
	 *            the regular expression path/name of the remote files
	 * @param local
	 *            the path/name to place the file on the local computer
	 * @param progress
	 * 
	 * @return SftpFile[]
	 * 
	 * @throws SftpStatusException
	 * @throws FileNotFoundException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	public SftpFile[] getFiles(String remote, String local,
			FileTransferProgress progress, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		return getFileMatches(remote, local, progress, resume);
	}

	/**
	 * <p>
	 * Upload the contents of an InputStream to the remote computer.
	 * </p>
	 * 
	 * @param in
	 * @param remote
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	/*
	 * public void putFiles(InputStream in, String remote) throws
	 * FileNotFoundException, SftpStatusException, SshException,
	 * TransferCancelledException { putFiles(in, remote, null, 0); }
	 */

	/**
	 * <p>
	 * Upload files to the remote computer reading from the specified <code>
	 * InputStream</code>. The InputStream is closed, even if the operation
	 * fails.
	 * </p>
	 * 
	 * @param in
	 *            the InputStream being read
	 * @param remote
	 *            the path/name of the destination file
	 * @param progress
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	/*
	 * public void putFiles(InputStream in, String remote, FileTransferProgress
	 * progress) throws FileNotFoundException, SftpStatusException,
	 * SshException, TransferCancelledException { putFiles(in, remote, progress,
	 * 0); }
	 * 
	 * public void putFiles(InputStream in, String remote, FileTransferProgress
	 * progress, long position) throws FileNotFoundException,
	 * SftpStatusException, SshException, TransferCancelledException {
	 * this.localI = in; this.position = position; // putFileMatches(remote,
	 * progress, true); put(remote, progress, true);
	 * 
	 * }
	 */

	/**
	 * Upload the contents of an InputStream to the remote computer.
	 * 
	 * @param in
	 * @param remote
	 * @param position
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 */
	/*
	 * public void putFiles(InputStream in, String remote, long position) throws
	 * FileNotFoundException, SftpStatusException, SshException,
	 * TransferCancelledException { putFiles(in, remote, null, position); }
	 */

	/**
	 * <p>
	 * Upload the contents of an InputStream to the remote computer.
	 * </p>
	 * 
	 * <p>
	 * When RegExpSyntax is set to NoSyntax the putFiles() methods act
	 * identically to the put() methods except for a different return type.
	 * </p>
	 * 
	 * <p>
	 * When RegExpSyntax is set to GlobSyntax or Perl5Syntax, putFiles() treats
	 * 'local' as a regular expression, and gets all the files in 'local''s
	 * parent directory that match the pattern. The default parent directory of
	 * local is the local cwd unless 'local' contains file seperators.
	 * </p>
	 * 
	 * <p>
	 * Examples can be found in SftpConnect.java
	 * 
	 * <p>
	 * Code Example: <blockquote>
	 * 
	 * <pre>
	 * // change reg exp syntax from default SftpClient.NoSyntax (no reg exp matching)
	 * // to SftpClient.GlobSyntax
	 * sftp.setRegularExpressionSyntax(SftpClient.GlobSyntax);
	 * // put all .doc files with 'rfc' in their names, in the 'docs/unsorted/' folder
	 * // relative to the local cwd, and copy them to the remote cwd.
	 * sftp.putFiles(&quot;docs/unsorted/*rfc*.doc&quot;);
	 * </pre>
	 * 
	 * </blockquote>
	 * </p>
	 * 
	 * @param local
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void putFiles(String local) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		putFiles(local, false);
	}

	/**
	 * Upload files to the remote computer
	 * 
	 * @param local
	 * @param resume
	 *            attempt to resume after an interrupted transfer
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void putFiles(String local, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		putFiles(local, (FileTransferProgress) null, resume);
	}

	/**
	 * <p>
	 * Upload files to the remote computer
	 * </p>
	 * 
	 * @param local
	 *            the regular expression path/name of the local files
	 * @param progress
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void putFiles(String local, FileTransferProgress progress)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		putFiles(local, progress, false);
	}

	/**
	 * <p>
	 * Upload files to the remote computer
	 * </p>
	 * 
	 * @param local
	 *            the regular expression path/name of the local files
	 * @param progress
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void putFiles(String local, FileTransferProgress progress,
			boolean resume) throws FileNotFoundException, SftpStatusException,
			SshException, TransferCancelledException {
		putFiles(local, pwd(), progress, resume);
	}

	/**
	 * Upload files to the remote computer
	 * 
	 * @param local
	 * @param remote
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void putFiles(String local, String remote)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		putFiles(local, remote, null, false);
	}

	/**
	 * Upload files to the remote computer
	 * 
	 * @param local
	 * @param remote
	 * @param resume
	 *            attempt to resume after an interrupted transfer
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void putFiles(String local, String remote, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		putFiles(local, remote, null, resume);
	}

	/**
	 * <p>
	 * Upload files to the remote computer. If the paths provided are not
	 * absolute the current working directory is used.
	 * </p>
	 * 
	 * @param local
	 *            the regular expression path/name of the local files
	 * @param remote
	 *            the path/name of the destination file
	 * @param progress
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void putFiles(String local, String remote,
			FileTransferProgress progress) throws FileNotFoundException,
			SftpStatusException, SshException, TransferCancelledException {
		putFiles(local, remote, progress, false);
	}

	/**
	 * make local copies of some of the variables, then call putfilematches,
	 * which calls "put" on each file that matches the regexp local.
	 * 
	 * @param local
	 *            the regular expression path/name of the local files
	 * @param remote
	 *            the path/name of the destination file
	 * @param progress
	 * @param resume
	 *            attempt to resume after an interrupted transfer
	 * 
	 * @throws SftpStatusException
	 * @throws SshException
	 * @throws TransferCancelledException
	 * @throws FileNotFoundException
	 */
	public void putFiles(String local, String remote,
			FileTransferProgress progress, boolean resume)
			throws FileNotFoundException, SftpStatusException, SshException,
			TransferCancelledException {
		putFileMatches(local, remote, progress, resume);
	}

	/**
	 * A simple wrapper class to provide an OutputStream to a RandomAccessFile
	 * 
	 * @author Lee David Painter
	 */
	static class RandomAccessFileOutputStream extends OutputStream {

		RandomAccessFile file;

		RandomAccessFileOutputStream(RandomAccessFile file) {
			this.file = file;
		}

		public void write(int b) throws IOException {
			file.write(b);
		}

		public void write(byte[] buf, int off, int len) throws IOException {
			file.write(buf, off, len);
		}

		public void close() throws IOException {
			file.close();
		}
	}

}
