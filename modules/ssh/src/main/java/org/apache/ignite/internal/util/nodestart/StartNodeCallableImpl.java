/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.util.nodestart;

import com.jcraft.jsch.*;
import org.apache.ignite.*;
import org.apache.ignite.cluster.*;
import org.apache.ignite.internal.*;
import org.apache.ignite.internal.cluster.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.resources.*;

import java.io.*;
import java.text.*;
import java.util.*;

import static org.apache.ignite.IgniteSystemProperties.*;

/**
 * SSH-based node starter.
 */
public class StartNodeCallableImpl implements StartNodeCallable {
    /** Default Ignite home path for Windows (taken from environment variable). */
    private static final String DFLT_IGNITE_HOME_WIN = "%IGNITE_HOME%";

    /** Default Ignite home path for Linux (taken from environment variable). */
    private static final String DFLT_IGNITE_HOME_LINUX = "$IGNITE_HOME";

    /** Default start script path for Linux. */
    private static final String DFLT_SCRIPT_LINUX = "bin/ignite.sh -v";

    /** Date format for log file name. */
    private static final SimpleDateFormat FILE_NAME_DATE_FORMAT = new SimpleDateFormat("MM-dd-yyyy--HH-mm-ss");

    /** Specification. */
    private final IgniteRemoteStartSpecification spec;

    /** Connection timeout. */
    private final int timeout;

    /** Logger. */
    @LoggerResource
    private IgniteLogger log;

    public static String fileNameLs;
    public static String fileNameNohupHelp;
    public static String fileNameMain;
    public static String fileNameMkdir1;
    public static String fileNameMkdir2;

    /**
     * Required by Externalizable.
     */
    public StartNodeCallableImpl() {
        spec = null;
        timeout = 0;

        assert false;
    }

    /**
     * Constructor.
     *
     * @param spec Specification.
     * @param timeout Connection timeout.
     */
    public StartNodeCallableImpl(IgniteRemoteStartSpecification spec, int timeout) {
        assert spec != null;

        this.spec = spec;
        this.timeout = timeout;
    }

    /** {@inheritDoc} */
    @Override public ClusterStartNodeResult call() {
        JSch ssh = new JSch();

        Session ses = null;

        try {
            if (spec.key() != null)
                ssh.addIdentity(spec.key().getAbsolutePath());

            log.info(">>>>> spec.username()=" + spec.username()  +" , spec.host()=" +spec.host()+" , spec.port()="+spec.port());
            
            ses = ssh.getSession(spec.username(), spec.host(), spec.port());

            if (spec.password() != null)
                ses.setPassword(spec.password());

            ses.setConfig("StrictHostKeyChecking", "no");

            ses.connect(timeout);
            
            log.info(">>>>> timeout=" + timeout);
            
            log.info(">>>>> ses.isConnected()" + ses.isConnected());

            boolean win = isWindows(ses);

            char separator = win ? '\\' : '/';

            spec.fixPaths(separator);

            String igniteHome = spec.igniteHome();

            if (igniteHome == null)
                igniteHome = win ? DFLT_IGNITE_HOME_WIN : DFLT_IGNITE_HOME_LINUX;

            String script = spec.script();

            if (script == null)
                script = DFLT_SCRIPT_LINUX;

            String cfg = spec.configuration();

            if (cfg == null)
                cfg = "";

            String startNodeCmd;
            String scriptOutputFileName = FILE_NAME_DATE_FORMAT.format(new Date()) + '-'
                + UUID.randomUUID().toString().substring(0, 8) + ".log";

            if (win)
                throw new UnsupportedOperationException("Apache Ignite cannot be auto-started on Windows from IgniteCluster.startNodes(…) API.");
            else { // Assume Unix.
                int spaceIdx = script.indexOf(' ');

                String scriptPath = spaceIdx > -1 ? script.substring(0, spaceIdx) : script;
                String scriptArgs = spaceIdx > -1 ? script.substring(spaceIdx + 1) : "";
                String rmtLogArgs = buildRemoteLogArguments(spec.username(), spec.host());
                String tmpDir = env(ses, "$TMPDIR", "/tmp/");
                String scriptOutputDir = tmpDir + "ignite-startNodes";

                fileNameMkdir1 = igniteHome + "/log_mkdir1.txt";
                fileNameMkdir2 = igniteHome + "/log_mkdir2.txt";

                shell(ses, "mkdir " + scriptOutputDir + " > " +  fileNameMkdir1 + " 2>& 1 &", log);
                shell(ses, "mkdir " + scriptOutputDir + " > " +  fileNameMkdir2 + " 2>& 1 &", log);

                // Mac os don't support ~ in double quotes. Trying get home path from remote system.
                if (igniteHome.startsWith("~")) {
                    String homeDir = env(ses, "$HOME", "~");

                    igniteHome = igniteHome.replaceFirst("~", homeDir);
                }
                
                fileNameMain = igniteHome + "/log1.txt";
                fileNameNohupHelp = igniteHome + "/log_nohup_help.txt";
                fileNameNohupHelp = igniteHome + "/log_ls.txt";

                startNodeCmd = new SB().
                    // Console output is consumed, started nodes must use Ignite file appenders for log.
                        a("nohup ").
                    a("\"").a(igniteHome).a('/').a(scriptPath).a("\"").
                    a(" ").a(scriptArgs).
                    a(!cfg.isEmpty() ? " \"" : "").a(cfg).a(!cfg.isEmpty() ? "\"" : "").a(rmtLogArgs).a(" > ").a(fileNameMain).a(" 2>& 1 &").
                    toString();
            }

            info("Starting remote node with SSH command: " + startNodeCmd, spec.logger(), log);

            shell(ses, "ls  > " + fileNameLs + " 2>& 1 &", log);
            
            shell(ses, "nohup --help  > " + fileNameNohupHelp + " 2>& 1 &", log);

            shell(ses, startNodeCmd, log);
            
            log.info(">>>>> Shelled");

            return new ClusterStartNodeResultImpl(spec.host(), true, null);
        }
        catch (IgniteInterruptedCheckedException e) {
            return new ClusterStartNodeResultImpl(spec.host(), false, e.getMessage());
        }
        catch (Exception e) {
            return new ClusterStartNodeResultImpl(spec.host(), false, X.getFullStackTrace(e));
        }
        finally {
            if (ses != null && ses.isConnected())
                ses.disconnect();
        }
    }

    /**
     * Executes command using {@code shell} channel.
     *
     * @param ses SSH session.
     * @param cmd Command.
     * @param log
     * @throws JSchException In case of SSH error.
     * @throws IOException If IO error occurs.
     * @throws IgniteInterruptedCheckedException If thread was interrupted while waiting.
     */
    public static void shell(Session ses, String cmd, IgniteLogger log) throws JSchException, IOException, IgniteInterruptedCheckedException {
        ChannelShell ch = null;
        
        log.info(">>>>> Shell. ses=" + ses + ", cmd=" + cmd);

        try {
            ch = (ChannelShell)ses.openChannel("shell");

            ch.connect();

            log.info(">>>>> Shell. Connected. ch.isConnect()=" + ch.isConnected());

            try (PrintStream out = new PrintStream(ch.getOutputStream(), true)) {
                log.info(">>>>> Shell. Printing to out");

                out.println(cmd);

                log.info(">>>>> Shell. Printed");

                U.sleep(1000);
            }
        }
        finally {
            if (ch != null && ch.isConnected()) {
                log.info(">>>>> Shell. Disconnecting");
                
                ch.disconnect();

                log.info(">>>>> Shell. Disconnected");
            }
        }
    }

    /**
     * Checks whether host is running Windows OS.
     *
     * @param ses SSH session.
     * @return Whether host is running Windows OS.
     * @throws JSchException In case of SSH error.
     */
    private boolean isWindows(Session ses) throws JSchException {
        try {
            return exec(ses, "cmd.exe") != null;
        }
        catch (IOException ignored) {
            return false;
        }
    }

    /**
     * Gets the value of the specified environment variable.
     *
     * @param ses SSH session.
     * @param name environment variable name.
     * @param dflt default value.
     * @return environment variable value.
     * @throws JSchException In case of SSH error.
     */
    private String env(Session ses, String name, String dflt) throws JSchException {
        try {
            return exec(ses, "echo " + name);
        }
        catch (IOException ignored) {
            return dflt;
        }
    }

    /**
     * Gets the value of the specified environment variable.
     *
     * @param ses SSH session.
     * @param cmd environment variable name.
     * @return environment variable value.
     * @throws JSchException In case of SSH error.
     * @throws IOException If failed.
     */
    private String exec(Session ses, String cmd) throws JSchException, IOException {
        ChannelExec ch = null;

        try {
            ch = (ChannelExec)ses.openChannel("exec");

            ch.setCommand(cmd);

            ch.connect();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(ch.getInputStream()))) {
                return reader.readLine();
            }
        }
        finally {
            if (ch != null && ch.isConnected())
                ch.disconnect();
        }
    }

    /**
     * Builds ignite.sh attributes to set up SSH username and password and log directory for started node.
     *
     * @param username SSH user name.
     * @param host Host.
     * @return {@code ignite.sh} script arguments.
     */
    private String buildRemoteLogArguments(String username, String host) {
        assert username != null;
        assert host != null;

        SB sb = new SB();

        sb.a(" -J-D").a(IGNITE_SSH_HOST).a("=\"").a(host).a("\"").
            a(" -J-D").a(IGNITE_SSH_USER_NAME).a("=\"").a(username).a("\"");

        return sb.toString();
    }

    /**
     * @param log Logger.
     * @return This callable for chaining method calls.
     */
    public StartNodeCallable setLogger(IgniteLogger log) {
        this.log = log;

        return this;
    }

    /**
     * Log info message to loggers.
     *
     * @param msg Message text.
     * @param loggers Loggers.
     */
    private void info(String msg, IgniteLogger... loggers) {
        for (IgniteLogger logger : loggers)
            if (logger != null && logger.isInfoEnabled())
                logger.info(msg);
    }
}
