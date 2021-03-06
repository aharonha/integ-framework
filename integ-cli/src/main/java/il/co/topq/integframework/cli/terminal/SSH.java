/*
 * Copyright 2005-2010 Ignis Software Tools Ltd. All rights reserved.
 */
package il.co.topq.integframework.cli.terminal;

import il.co.topq.integframework.reporting.Reporter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

import org.testng.ITestResult;

import ch.ethz.ssh2.*;
import ch.ethz.ssh2.channel.Channel;

/**
 * A terminal used for SSH Connection
 */
public class SSH extends Terminal {
	
	protected String hostname;

	protected String username;

	protected String password;
	
	protected Connection conn = null;

	protected Session sess = null;
	
	//ssh port forwarding
	protected LocalPortForwarder lpf = null;

	protected int sourcePort = -1;

	protected int destinationPort = -1;
	
	protected int port = 22;

	protected boolean xtermTerminal = true;
	
	public SSH(String hostnameP, String usernameP, String passwordP) {
		this(hostnameP, usernameP, passwordP, -1, -1, true);
	}
	
	public SSH(String hostnameP, String usernameP, String passwordP, int sourceTunnelPort, int destinationTunnelPort) {
		this(hostnameP, usernameP, passwordP, sourceTunnelPort, destinationTunnelPort, true);
	}

	public SSH(String hostnameP, String usernameP, String passwordP, int sourceTunnelPort, int destinationTunnelPort, boolean _xtermTerminal) {
		super();
		hostname = hostnameP;
		username = usernameP;
		password = passwordP;
		sourcePort = sourceTunnelPort;
		destinationPort =destinationTunnelPort;
		xtermTerminal = _xtermTerminal;
	}

	@Override
	public void connect() throws IOException {
		boolean isAuthenticated = false;
		/* Create a connection instance */
		Reporter.log("Connecting to " + hostname + " via " + getConnectionName());
		conn = new Connection(hostname, getPort());

		/* Now connect */
		try {
			conn.connect();
		} catch (IOException e) {
			Reporter.log("Connection to "+ hostname + " Failed", e, ITestResult.SUCCESS_PERCENTAGE_FAILURE);
			throw e;
		}

		String[] authMethods = conn.getRemainingAuthMethods(username);
		// Check what connection options are available to us
		synchronized (System.out){
			System.out.println("The supported auth Methods are:");
			for(String method: authMethods) {
				System.out.println(method);
			}
		}
//		boolean privateKeyAuthentication = false;
		boolean passAuthentication = false;
		for (int i = 0; i < authMethods.length; i++) {
			if (authMethods[i].equalsIgnoreCase("password")) {
				// we can authenticate with a password
				passAuthentication = true;
			}
		}
		if(Arrays.asList(authMethods).contains("publickey")){
			// we can authenticate with a RSA private key
//			privateKeyAuthentication=true;
		}
		
		/* Authenticate */
		if (passAuthentication) {
			try {
				isAuthenticated = conn.authenticateWithPassword(username, password);
			} catch (Exception e) {
				isAuthenticated = false;
			}
		}
		if (isAuthenticated == false) {
			// we're still not authenticated - try keyboard interactive
			conn.authenticateWithKeyboardInteractive(username, new InteractiveLogic());
		}


		if (sourcePort > -1 && destinationPort > -1) {
			lpf = conn.createLocalPortForwarder(sourcePort, "localhost" , destinationPort);
		}
		
		/* Create a session */
		sess = conn.openSession();
		
		if (xtermTerminal) {
			sess.requestPTY("xterm", 80, 24, 640, 480, null);
		}else {
			sess.requestPTY("dumb", 200, 50, 0, 0, null);
		}
		
		sess.startShell();
		
		in =  sess.getStdout();
		out = sess.getStdin();
	}

	protected int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	@Override
	public void disconnect() {
		if (lpf != null) {
			try {
				lpf.close();
			} catch (IOException e) {
			}
		}
		if (sess != null) {
			sess.close();
		}
		if (conn != null) {
			conn.close();
		}
	}

	@Override
	public boolean isConnected() {
		return sess.getState() == Channel.STATE_OPEN;
	}

	@Override
	public String getConnectionName() {
		return "SSH";
	}
	
	/**
	 * get an {@link InputStream} for a remote file<br /> 
	 * The session for opened for this SCP transfer must be closed using
	 * {@link InputStream#close()}
	 * @param remoteFile
	 * @return
	 * @throws IOException
	 */
	public InputStream get(final String remoteFile) throws IOException{
		return new SCPClient(getConn()).get(remoteFile);
	}
	
	/**
	 * get an {@link OutputStream} for a remote file <br />
	 * The session for opened for this SCP transfer must be closed using
	 * {@link OutputStream#close()}
	 *
	 * @param remoteFile
	 * @param length The size of the file to send
	 * @param remoteTargetDirectory
	 * @param mode
	 * @return
	 * @throws IOException
	 */
	public OutputStream put(final String remoteFile, long length, String remoteTargetDirectory, String mode) throws IOException{
		return new SCPClient(getConn()).put(remoteFile, length, remoteTargetDirectory, mode);
	}
	/**
	 * The logic that one has to implement if "keyboard-interactive" 
	 * authentication shall be supported.
	 */
	class InteractiveLogic implements InteractiveCallback {
		/* the callback may be invoked several times, depending on how many questions-sets the server sends */
		@Override
		public String[] replyToChallenge(String name, String instruction, int numPrompts, String[] prompt, boolean[] echo) throws IOException {
			/* Often, servers just send empty strings for "name" and "instruction" */

			String[] result = new String[numPrompts];

			for (int i = 0; i < numPrompts; i++) {
				if (prompt[i].toLowerCase().startsWith("password:")) {
					result[i] = password;
				} else {
					// we don't know how to handle the prompt
					System.out.print("SSH client - Unknown prompt type returned (" + prompt[i] + ")\n");
					result[i] = "";
				}
			}

			return result;
		}

	}

	protected String getHostname() {
		return hostname;
	}

	protected void setHostname(String hostname) {
		this.hostname = hostname;
	}

	protected String getUsername() {
		return username;
	}

	protected void setUsername(String username) {
		this.username = username;
	}

	protected String getPassword() {
		return password;
	}

	protected void setPassword(String password) {
		this.password = password;
	}

	protected Connection getConn() {
		return conn;
	}

	protected void setConn(Connection conn) {
		this.conn = conn;
	}

	protected int getSourcePort() {
		return sourcePort;
	}

	protected void setSourcePort(int sourcePort) {
		this.sourcePort = sourcePort;
	}

	protected int getDestinationPort() {
		return destinationPort;
	}

	protected void setDestinationPort(int destinationPort) {
		this.destinationPort = destinationPort;
	}
}
