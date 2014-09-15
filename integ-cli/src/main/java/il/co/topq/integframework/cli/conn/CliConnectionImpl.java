/*
 * Created on 19/07/2005
 *
 * Copyright 2005-2010 Ignis Software Tools Ltd. All rights reserved.
 */
package il.co.topq.integframework.cli.conn;

import il.co.topq.integframework.AbstractModuleImpl;
import il.co.topq.integframework.assertion.AbstractAssertionLogic;
import il.co.topq.integframework.assertion.IAssertionLogic;
import il.co.topq.integframework.cli.terminal.*;
import il.co.topq.integframework.reporting.Reporter;
import il.co.topq.integframework.reporting.Reporter.Color;

import java.io.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * This is a default implementation for CliConnection your implementation should
 * extend this one.
 * 
 * @author guy.arieli
 * 
 */
public abstract class CliConnectionImpl extends AbstractModuleImpl implements CliConnection {

	public static enum EnumConnectionType {
		COM("com"), RS232("rs232"), TELNET("telnet"), SSH("ssh"), SSH_RSA("ssh-rsa");
		EnumConnectionType(String value) {
			this.value = value;
		}

		private String value;

		public String value() {
			return value;
		}
	}

	protected volatile Cli cli;

	protected Terminal terminal;

	protected HashMap<String, Position> positions = new HashMap<String, Position>();

	protected int port = 23;

	protected String user;

	protected String password;

	protected String cliLogFile = null;

	protected boolean useBuffer = false;

	protected BufferInputStream buffer;

	protected boolean connected = false;

	protected boolean connectOnInit = true;

	protected String protocol = EnumConnectionType.TELNET.value();

	protected long lastCommandTime = 0;

	// set to true in windows XP telnet server
	protected boolean dump = false;

	protected boolean delayedTyping = false;

	protected boolean graceful = false;

	protected String host;

	protected boolean useTelnetInputStream = false;

	protected boolean dummy = false;

	protected boolean forceIgnoreAnyErrors = false;

	// used for win2K server
	protected boolean vt100Filter = false;

	// will generate enter upon login (like in rs232)
	protected boolean leadingEnter = false;

	private ArrayList<Prompt> prompts = new ArrayList<Prompt>();

	// number of times the client will try to connect to the remote cli
	// agent.
	protected int connectRetries = 3;

	/**
	 * Monitor the allowed idle time
	 */
	IdleMonitor idleMonitor = null;

	/**
	 * The max permitted idle time of machine. -1 will disable any action
	 */
	long maxIdleTime = -1;

	/**
	 * this is the command enter string, can be set from the sut
	 */
	protected String enterStr = null;

	/**
	 * The key delay when sending keys to the terminal. Only relevant when
	 * delayTyping is set to true
	 */
	protected long keyTypingDelay = 20;

	/**
	 * Whether to ignore backspace characters or not
	 */
	private boolean ignoreBackSpace = false;

	private String charSet = "ASCII";

	/**
	 * SSH2 private key -RSA (ppk or pem file)
	 */
	private File privateKey;

	public boolean isConnectOnInit() {
		return connectOnInit;
	}

	public void setConnectOnInit(boolean connectOnInit) {
		this.connectOnInit = connectOnInit;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getUser() {
		return user;
	}

	public void setUser(String user) {
		this.user = user;
	}

	public CliConnectionImpl() {
		Position[] positionArray = getPositions();
		if (positionArray != null) {
			for (int positionIndex = 0; positionIndex < positionArray.length; positionIndex++) {
				positions.put(positionArray[positionIndex].getName(), positionArray[positionIndex]);
			}
		}
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	protected void navigate(CliCommand command, boolean toPosition) throws Exception {
		if (command.getPosition() == null) {
			return;
		}
		Position currentPosition = positions.get(command.getPosition());
		if (currentPosition == null) {
			throw new Exception("Fail to find position: " + command.getPosition());
		}

		String[] commands = toPosition ? currentPosition.getEnters() : currentPosition.getExits();

		if (commands != null) {
			for (int ccommandIndex = 0; ccommandIndex < commands.length; ccommandIndex++) {
				String cmd = changeCommand(commands[ccommandIndex], command.getProperties());
				cli.setDontWaitForPrompts(command.isDontWaitForPrompts());
				cli.command(cmd, command.getTimeout(), true);
				command.addResult(cli.getResult());

			}
		}
	}

	public void navigateToPosition(CliCommand command) throws Exception {
		navigate(command, true);
	}

	public void returnFromPosition(CliCommand command) throws Exception {
		navigate(command, false);
	}

	private boolean useThreads = false;
	private Thread initializer = null;

	public void init() throws Exception {
		final List<Exception> ex = new ArrayList<Exception>();
		Runnable r = new Runnable() {

			@Override
			public void run() {
				// TODO Auto-generated method stub
				if (isConnectOnInit()) {
					try {
						connect();
					} catch (Exception e) {
						ex.add(e);
					}
				}
			}
		};
		name = this.getName() != null ? this.getName() : this.getHost();
		initializer = new Thread(null, r, getName() + " initializer");
		initializer.setDaemon(true);

		if (useThreads) {
			initializer.start();
		} else {
			r.run();
			if (ex.size() == 1) {
				throw ex.get(0);
			}
		}

	}

	public void connect() throws Exception {
		activateIdleMonitor();
		connectRetries = connectRetries <= 0 ? 1 : connectRetries;
		if (!Thread.currentThread().equals(initializer)) {
			initializer.join();
		}
		synchronized (this) {

			for (int retriesCounter = 0; retriesCounter < connectRetries; retriesCounter++) {
				try {
					// TODO:
					// Reporter.getCurrentTestResult().setStatus(ITestResult.SUCCESS);
					internalConnect();
					break;
				} catch (Exception e) {
					Reporter.log("Failed connecting  " + getHost() + ". Attempt " + (retriesCounter + 1) + ".  " + e.getMessage());
					try {
						disconnect();
					} catch (Throwable t) {
					}
					if (retriesCounter == connectRetries - 1) {
						throw e;
					}
				} finally {
					// TODO:
					// Reporter.getCurrentTestResult().setStatus(ITestResult.FAILURE);
				}
			}
		}
	}

	private void internalConnect() throws Exception {
		if (host == null) {
			throw new Exception("Default connection ip/comm is not configured");
		}
		Reporter.log("Init cli, host: " + host);
		if (dummy) {
			return;
		}
		// Terminal t;
		boolean isRs232 = false;

		boolean isRsa = false;
		if (host.toLowerCase().startsWith(EnumConnectionType.COM.value())
				|| protocol.toLowerCase().equals(EnumConnectionType.RS232.value())) {
			// syntax for serial connection found
			isRs232 = true;
			String[] params = host.split("\\;");
			if (params.length < 5) {
				throw new Exception("Unable to extract parameters from host: " + host);
			}
			terminal = new RS232(params[0], Integer.parseInt(params[1]), Integer.parseInt(params[2]), Integer.parseInt(params[3]),
					Integer.parseInt(params[4]));
		} else if (protocol.toLowerCase().equals(EnumConnectionType.SSH.value())) {
			terminal = new SSH(host, user, password);
		} else if (protocol.toLowerCase().equals(EnumConnectionType.SSH_RSA.value())) {
			terminal = new SSHWithRSA(host, user, password, privateKey);
			prompts.add(new Prompt("$", false, true));
			prompts.add(new Prompt("]$", false, true));

			isRsa = true;
		} else {
			terminal = new Telnet(host, port, useTelnetInputStream);
			if (dump) {
				((Telnet) terminal).setVtType(null);
			}
		}

		terminal.setCharSet(getCharSet());

		terminal.setIgnoreBackSpace(isIgnoreBackSpace());

		if (delayedTyping) {
			terminal.setKeyTypingDelay(keyTypingDelay);
		}
		if (terminal instanceof SSH && getPort() != 23) {
			((SSH) terminal).setPort(getPort());
		}
		cli = new Cli(terminal);
		if (enterStr != null) {
			setEnterStr(enterStr);
		}
		cli.setGraceful(graceful);
		if (useBuffer) {
			buffer = new BufferInputStream();
			terminal.addFilter(buffer);
			buffer.startThread();
		}

		if (vt100Filter) {
			terminal.addFilter(new VT100FilterInputStream());
		}
		Prompt[] prompts = getAllPrompts();
		for (int i = 0; i < prompts.length; i++) {
			cli.addPrompt(prompts[i]);
		}
		if (isRs232 || leadingEnter) {
			cli.command("");
		} else if (isRsa) {
			cli.login();
		} else {
			cli.login(60000, delayedTyping);
		}
		connected = true;
	}

	@Override
	public void close() {
		if (idleMonitor != null) {
			idleMonitor.setStop();
			idleMonitor.interrupt();
		}
		disconnect();
		isClosed = true;
	}

	public void disconnect() {
		connected = false;
		if (cli != null) {
			try {
				cli.close();
			} catch (IOException e) {
			}
		}
	}

	public void setPosition(Position position) {
		positions.put(position.getName(), position);
	}

	public void handleCliCommand(String title, CliCommand command) throws Exception {
		if (command.isClone()) {
			CliConnectionImpl cloned = (CliConnectionImpl) this.clone();
			try {
				handleCliCommand(cloned, title, command);
				setActual(cloned.getActual());
			} finally {
				cloned.close();
			}
		} else {
			handleCliCommand(this, title, command);
		}
	}

	/**
	 * Activates the command <code>command</code> on given
	 * <code>connection</code> on given <code>CliConnection<code>.
	 * 
	 * Procedure flow:<br>
	 * 1. connects to remote machine if needed<br>
	 * 2. Runs the command<br>
	 * 3. Performs report operation & throws an exception in case of an error.<br>
	 * (and ignore error flags were not raised)<br>
	 * 4. Performs Analysis if one or more analyzers are defined <br>
	 * (and ignore error flags were not raised)
	 */
	public static void handleCliCommand(CliConnectionImpl cli, String title, CliCommand command) throws Exception {
		synchronized (cli) {

			if (!cli.isConnectOnInit() && !cli.isConnected() || cli.useThreads) {
				cli.connect();
			}
			cli.command(command);

			cli.setActual(command.getResult());
			if (command.isFailed() && (!command.isIgnoreErrors()) && (!cli.isForceIgnoreAnyErrors())) {
				Reporter.log(title + ", " + command.getFailCause(), command.getResult(), Color.RED);
				Exception e = command.getThrown();
				if (e != null) {
					throw e;
				}
				throw new Exception("Cli command failed");
			}

			if (!command.isSilent()) {
				Reporter.log(title, command.getResult());
			}
			if (command.isIgnoreErrors() || (cli.isForceIgnoreAnyErrors())) {
				;
			} else {
				List<IAssertionLogic<String>> analyzers = command.getAnalyzers();
				if (analyzers != null) {
					for (IAssertionLogic<String> analyzer : analyzers) {
						analyzer.setActual(cli.getActual(String.class));
						analyzer.doAssertion();
						if (analyzer instanceof AbstractAssertionLogic<?>) {
							AbstractAssertionLogic<String> stringAssertionLogic = (AbstractAssertionLogic<String>) analyzer;
							if (!(command.isSilent() && stringAssertionLogic.isStatus())) {
								Reporter.log(stringAssertionLogic.getTitle(), stringAssertionLogic.getMessage(),
										stringAssertionLogic.isStatus());
							}
						}
					}
				}
			}
			cli.setForceIgnoreAnyErrors(false);
		}
	}

	public synchronized void command(CliCommand command) {

		lastCommandTime = System.currentTimeMillis();
		cli.setDontWaitForPrompts(command.isDontWaitForPrompts());
		try {
			navigateToPosition(command);
		} catch (Exception e1) {
			command.setFailCause("Navigate to position failed");
			command.setThrown(e1);
			command.setFailed(true);
			return;
		}
		String[] commands = command.getCommands();
		for (int commandIndex = 0; commandIndex < commands.length; commandIndex++) {
			int retries = 0;
			while (true) {
				if (!(retries < command.getNumberOfRetries())) {
					command.setFailCause("system is busy after " + command.getNumberOfRetries() + " retries");
					command.setFailed(true);
					return;
				}
				String cmd = changeCommand(commands[commandIndex], command.getProperties());

				try {
					if (command.getPrompts() != null) {
						cli.command(cmd, command.getTimeout(), command.isAddEnter(), command.isDelayTyping(), null,
								command.getPrompts());
					} else {
						cli.command(cmd, command.getTimeout(), command.isAddEnter(), command.isDelayTyping(),
								command.getPromptString());
					}

				} catch (Exception e) {
					command.addResult(cli.getResult());
					command.setFailCause("cli command failed: " + cmd);
					command.setThrown(e);
					command.setFailed(true);
					return;
				}
				String lastResult = cli.getResult();

				if (command.isSuppressEcho()) {
					int indexOfCommand = lastResult.indexOf(cmd);
					if (indexOfCommand > -1) {
						lastResult = lastResult.substring(indexOfCommand + cmd.length());
					}
				}

				command.addResult(lastResult);
				command.setResultPrompt(cli.getResultPrompt());
				setActual(command.getResult());

				// If log file name (+path) defined at the sut, CLI results will
				// be save also to this file
				// Add to the sut file under <conn><cli> the tag <cliLogFile>
				if (cliLogFile != null) {
					try {
						BufferedWriter out = new BufferedWriter(new FileWriter(cliLogFile, true));
						out.write(lastResult);
						out.close();
					} catch (IOException e) {
						command.setFailCause("Writing CLI buffer to file " + cliLogFile + " failed");
						command.setThrown(e);
						command.setFailed(true);
						return;
					}
				}

				if (command.getRetryString() == null) {
					break;
				} else {
					if (lastResult.indexOf(command.getRetryString()) < 0) {
						break;
					}

				}
				try {
					Thread.sleep(command.getDelayInRetries());
				} catch (InterruptedException e2) {
					command.setFailCause("Sleep failed");
					command.setThrown(e2);
					command.setFailed(true);
					return;
				}
				retries++;
			}
		}
		try {
			returnFromPosition(command);
		} catch (Exception e) {
			command.setFailCause("Navigate from position failed");
			command.setThrown(e);
			command.setFailed(true);
			return;
		}
	}

	public boolean isDummy() {
		return dummy;
	}

	public void setDummy(boolean dummy) {
		this.dummy = dummy;
	}

	public boolean isUseTelnetInputStream() {
		return useTelnetInputStream;
	}

	public void setUseTelnetInputStream(boolean useTelnetInputStream) {
		this.useTelnetInputStream = useTelnetInputStream;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	protected String changeCommand(String command, Properties p) {
		if (p == null) {
			return command;
		}
		Enumeration<Object> iter1 = p.keys();
		while (iter1.hasMoreElements()) {
			String key = (String) iter1.nextElement();
			command = command.replaceAll("<" + key + ">", p.getProperty(key));
		}
		return command;
	}

	public void waitForNotifications(String[] notifications, long timeout) throws Exception {
		cli.command("", timeout, false, false, notifications);
		cli.command("", timeout, false, false, (String) null);
	}

	public String getCliBuffer() {
		if (useBuffer) {
			return buffer.getBuffer();
		}
		return null;
	}

	public void cleanCliBuffer() {
		if (useBuffer) {
			buffer.clean();
		}
	}

	public boolean isUseBuffer() {
		return useBuffer;
	}

	public void setUseBuffer(boolean useBuffer) {
		this.useBuffer = useBuffer;
	}

	public boolean isConnected() {
		if (cli == null) {
			return false;
		}
		return cli.isConnected();
	}

	public void setConnected(boolean connected) {
		this.connected = connected;
	}

	public String getProtocol() {
		return protocol;
	}

	public void setProtocol(String protocol) {
		this.protocol = protocol;
	}

	public long getLastCommandTime() {
		return lastCommandTime;
	}

	public long getMaxIdleTime() {
		return maxIdleTime;
	}

	public void setMaxIdleTime(long maxIdleTime) {
		this.maxIdleTime = maxIdleTime;
	}

	/**
	 * activates the IdleMonitor (if it wasn't activated allready) don't use if
	 * idleMonitor was allready active
	 */
	public synchronized void activateIdleMonitor() {
		if (maxIdleTime > 0) {
			lastCommandTime = System.currentTimeMillis();
			idleMonitor = new IdleMonitor(this, maxIdleTime);
			idleMonitor.start();
		}
	}

	/**
	 * deactivates the IdleMonitor
	 */
	public synchronized void deactivateIdleMonitor() {
		if (idleMonitor != null) {
			try {
				idleMonitor.setStop();
				idleMonitor.join();
			} catch (InterruptedException e) {
				Reporter.log("Deactivating idle monitor failed", e);
			} finally {
				idleMonitor = null;
			}
		}
	}

	public String getCliLogFile() {
		return cliLogFile;
	}

	public void setCliLogFile(String resultFileName) {
		this.cliLogFile = resultFileName;
	}

	public boolean isDump() {
		return dump;
	}

	public void setDump(boolean dump) {
		this.dump = dump;
	}

	public void setGraceful(boolean graceful) {
		this.graceful = graceful;
		if (cli != null) {
			cli.setGraceful(graceful);
		}
	}

	public boolean isGraceful() {
		return graceful;
	}

	public void setPrintStream(PrintStream printStream) {
		cli.setPrintStream(printStream);
	}

	public Prompt getResultPrompt() {
		if (cli == null) {
			return null;
		}
		return cli.getResultPrompt();
	}

	public String read() throws Exception {
		return cli.read();
	}

	public void reconnect() {

		try {
			cli.reconnect();
		} catch (Exception e) {
		}
	}

	public Object clone() throws CloneNotSupportedException {
		try {
			CliConnectionImpl newImpl = (CliConnectionImpl) getClass().getClassLoader().loadClass(getClass().getName())
					.newInstance();
			newImpl.port = port;
			newImpl.user = user;
			newImpl.password = password;
			newImpl.cliLogFile = cliLogFile;
			newImpl.useBuffer = useBuffer;
			newImpl.buffer = buffer;
			newImpl.connected = false;
			newImpl.connectOnInit = connectOnInit;
			newImpl.protocol = protocol;
			newImpl.lastCommandTime = 0;
			newImpl.dump = dump;
			newImpl.delayedTyping = delayedTyping;
			newImpl.keyTypingDelay = keyTypingDelay;
			newImpl.graceful = graceful;
			newImpl.host = host;
			newImpl.useTelnetInputStream = useTelnetInputStream;
			newImpl.dummy = dummy;
			newImpl.vt100Filter = vt100Filter;
			newImpl.leadingEnter = leadingEnter;
			return newImpl;
		} catch (Exception e) {
			throw new RuntimeException("Failed cloning CliConnection", e);
		}
	}

	public boolean isForceIgnoreAnyErrors() {
		return forceIgnoreAnyErrors;
	}

	public void setForceIgnoreAnyErrors(boolean forceIgnoreAnyErrors) {
		this.forceIgnoreAnyErrors = forceIgnoreAnyErrors;
	}

	private Prompt[] getAllPrompts() {
		ArrayList<Prompt> allPrompts = new ArrayList<Prompt>();
		allPrompts.addAll(prompts);
		Prompt[] pr = getPrompts();
		for (Prompt p : pr) {
			allPrompts.add(p);
		}
		return allPrompts.toArray(new Prompt[0]);
	}

	public void addPrompts(Prompt[] promptsToAdd) {
		if (promptsToAdd == null) {
			return;
		}
		for (Prompt p : promptsToAdd) {
			if (terminal != null) {
				terminal.addPrompt(p);
			}
			prompts.add(p);
		}
	}

	public void setPrompts(Prompt[] promptsToAdd) {
		prompts = new ArrayList<Prompt>();
		if (terminal != null) {
			terminal.removePrompts();
		}
		if (promptsToAdd == null) {
			return;
		}
		for (Prompt p : promptsToAdd) {
			if (terminal != null) {
				terminal.addPrompt(p);
			}
			prompts.add(p);
		}
	}

	public void setEnterStr(String enterStr) {
		// replace \r string with the '\r' char (the same for \n)
		enterStr = enterStr.replaceAll(Pattern.quote("\\r"), "\r");
		enterStr = enterStr.replaceAll(Pattern.quote("\\n"), "\n");
		if (cli == null) {
			this.enterStr = enterStr;
		} else {
			cli.setEnterStr(enterStr);
		}
	}

	public void setScrollEndTimeout(long timeout) {
		terminal.setScrollEndTimeout(timeout);
	}

	public void addFilter(InOutInputStream stream) {
		terminal.addFilter(stream);
	}

	public void sendString(String toSend, boolean delayedTyping) throws Exception {
		terminal.sendString(toSend, delayedTyping);
	}

	public String getEnterStr() {
		if (cli == null) {
			return enterStr;
		}
		return cli.getEnterStr();
	}

	public int getConnectRetries() {
		return connectRetries;
	}

	public void setConnectRetries(int connectRetries) {
		this.connectRetries = connectRetries;
	}

	public boolean isVt100Filter() {
		return vt100Filter;
	}

	public void setVt100Filter(boolean vt100Filter) {
		this.vt100Filter = vt100Filter;
	}

	public boolean isLeadingEnter() {
		return leadingEnter;
	}

	public void setLeadingEnter(boolean leadingEnter) {
		this.leadingEnter = leadingEnter;
	}

	public long getKeyTypingDelay() {
		if (terminal != null) {
			return terminal.getKeyTypingDelay();
		}
		return keyTypingDelay;
	}

	public void setKeyTypingDelay(long keyTypingDelay) {
		this.keyTypingDelay = keyTypingDelay;
		if (terminal != null) {
			terminal.setKeyTypingDelay(keyTypingDelay);
		}
	}

	/**
	 * Whether to ignore backspace characters or not
	 * 
	 * @param ignoreBackSpace
	 */
	public boolean isIgnoreBackSpace() {
		return ignoreBackSpace;
	}

	/**
	 * Whether to ignore backspace characters or not
	 * 
	 * @param ignoreBackSpace
	 */
	public void setIgnoreBackSpace(boolean ignoreBackSpace) {
		this.ignoreBackSpace = ignoreBackSpace;

		if (terminal != null) {
			terminal.setIgnoreBackSpace(ignoreBackSpace);
		}
	}

	public void setCharSet(String charSet) {
		this.charSet = charSet;
	}

	public String getCharSet() {
		return charSet;
	}

	public File getPrivateKey() {
		return privateKey;
	}

	public void setPrivateKey(File privateKey) {
		this.privateKey = privateKey;
	}

	public void setUseThreads(boolean useThreads) {
		this.useThreads = useThreads;
	}

}
