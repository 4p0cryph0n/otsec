/*
 * MODIFICATION NOTICE
 * -------------------
 *
 * This file is a modified version of the original ConsoleClient provided by
 * the j60870 project (Fraunhofer ISE / OpenMUC). This file has been modified by 4p0cryph0n.
 *
 * Modifications have been made for the purpose of:
 *   - Simulating a realistic IEC 60870-5-104 client (master)
 *   - Compatible with breakers added to SampleServer
 *
 * These changes are intended solely for local lab use, education,
 * and OT/ICS security research. No real-world infrastructure is targeted
 * or controlled by this software.
 *
 * Original project: https://www.openmuc.org
 *
 * The original license terms (GNU GPL v3 or later) apply to this file
 * and to all derivative works.
 */

package org.openmuc.j60870.app;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.openmuc.j60870.*;
import org.openmuc.j60870.ie.*;
import org.openmuc.j60870.internal.cli.*;

public final class ConsoleClient {

    private static final String INTERROGATION_ACTION_KEY = "i";
    private static final String COUNTER_INTERROGATION_ACTION_KEY = "ci";
    private static final String CLOCK_SYNC_ACTION_KEY = "c";
    private static final String SINGLE_COMMAND_SELECT = "s";
    private static final String SINGLE_COMMAND_EXECUTE = "e";
    private static final String SEND_STOPDT = "p";
    private static final String SEND_STARTDT = "t";

    private static final StringCliParameter hostParam = new CliParameterBuilder("-h")
            .setDescription("The IP/domain address of the server you want to access.")
            .setMandatory()
            .buildStringParameter("host");

    private static final IntCliParameter portParam = new CliParameterBuilder("-p")
            .setDescription("The port to connect to.")
            .buildIntParameter("port", 2404);

    private static final IntCliParameter commonAddrParam = new CliParameterBuilder("-ca")
            .setDescription("The address of the target station.")
            .buildIntParameter("common_address", 65535);

    private static final IntCliParameter startDtRetries = new CliParameterBuilder("-r")
            .setDescription("Send start DT retries.")
            .buildIntParameter("start_DT_retries", 1);

    private static final IntCliParameter connectionTimeout = new CliParameterBuilder("-ct")
            .setDescription("Connection timeout t0.")
            .buildIntParameter("connection_timeout", 20_000);

    private static final IntCliParameter messageFragmentTimeout = new CliParameterBuilder("-mft")
            .setDescription("Message fragment timeout.")
            .buildIntParameter("message_fragment_timeout", 5_000);

    private static Connection connection;
    private static final ActionProcessor actionProcessor =
            new ActionProcessor(new ActionExecutor());

    /* ---------------- Connection events ---------------- */

    private static class ClientEventListener implements ConnectionEventListener {

        @Override
        public void newASdu(Connection connection, ASdu aSdu) {
            log("\nReceived ASDU:\n", aSdu.toString());
        }

        @Override
        public void connectionClosed(Connection connection, IOException e) {
            log("Connection closed: ",
                    e.getMessage() == null ? "unknown" : e.getMessage());
            actionProcessor.close();
        }

        @Override
        public void dataTransferStateChanged(Connection connection, boolean stopped) {
            log("Data transfer ", stopped ? "stopped" : "started");
        }
    }

    /* ---------------- Action executor ---------------- */

    private static class ActionExecutor implements ActionListener {

        @Override
        public void actionCalled(String actionKey) throws ActionException {
            try {
                switch (actionKey) {

                case INTERROGATION_ACTION_KEY:
                    log("** Sending general interrogation");
                    connection.interrogation(
                            commonAddrParam.getValue(),
                            CauseOfTransmission.ACTIVATION,
                            new IeQualifierOfInterrogation(20)
                    );
                    break;

                case COUNTER_INTERROGATION_ACTION_KEY:
                    log("** Sending counter interrogation");
                    connection.counterInterrogation(
                            commonAddrParam.getValue(),
                            CauseOfTransmission.ACTIVATION,
                            new IeQualifierOfCounterInterrogation(5, 0)
                    );
                    break;

                case CLOCK_SYNC_ACTION_KEY:
                    log("** Sending clock sync");
                    connection.synchronizeClocks(
                            commonAddrParam.getValue(),
                            new IeTime56(System.currentTimeMillis())
                    );
                    break;

                case SINGLE_COMMAND_SELECT: {
                    int ioa = readIoa();
                    boolean desired = readState();

                    log("** SELECT breaker IOA=" + ioa +
                            " state=" + (desired ? "CLOSE" : "OPEN"));

                    connection.singleCommand(
                            commonAddrParam.getValue(),
                            CauseOfTransmission.ACTIVATION,
                            ioa,
                            new IeSingleCommand(desired, 0, true)
                    );
                    break;
                }

                case SINGLE_COMMAND_EXECUTE: {
                    int ioa = readIoa();
                    boolean desired = readState();

                    log("** EXECUTE breaker IOA=" + ioa +
                            " state=" + (desired ? "CLOSE" : "OPEN"));

                    connection.singleCommand(
                            commonAddrParam.getValue(),
                            CauseOfTransmission.ACTIVATION,
                            ioa,
                            new IeSingleCommand(desired, 0, false)
                    );
                    break;
                }

                case SEND_STOPDT:
                    connection.stopDataTransfer();
                    break;

                case SEND_STARTDT:
                    connection.startDataTransfer();
                    break;

                default:
                    break;
                }
            }
            catch (Exception e) {
                throw new ActionException(e);
            }
        }

        private int readIoa() throws IOException {
            log("Enter breaker IOA (e.g. 1001, 1002, 1003):");
            return Integer.parseInt(actionProcessor.getReader().readLine());
        }

        private boolean readState() throws IOException {
            log("Enter state (1 = CLOSE / ON, 0 = OPEN / OFF):");
            return actionProcessor.getReader().readLine().trim().equals("1");
        }

        @Override
        public void quit() {
            connection.close();
        }
    }

    /* ---------------- Main ---------------- */

    public static void main(String[] args) {

        List<CliParameter> cliParameters = new ArrayList<>();
        cliParameters.add(hostParam);
        cliParameters.add(portParam);
        cliParameters.add(commonAddrParam);

        CliParser cliParser = new CliParser(
                "j60870-console-client",
                "IEC 60870-5-104 interactive console client"
        );
        cliParser.addParameters(cliParameters);

        try {
            cliParser.parseArguments(args);
        }
        catch (CliParseException e) {
            log(cliParser.getUsageString());
            return;
        }

        InetAddress address;
        try {
            address = InetAddress.getByName(hostParam.getValue());
        }
        catch (UnknownHostException e) {
            log("Unknown host");
            return;
        }

        ClientConnectionBuilder builder =
                new ClientConnectionBuilder(address)
                        .setPort(portParam.getValue())
                        .setConnectionTimeout(connectionTimeout.getValue())
                        .setMessageFragmentTimeout(messageFragmentTimeout.getValue())
                        .setConnectionEventListener(new ClientEventListener());

        try {
            connection = builder.build();
        }
        catch (IOException e) {
            log("Unable to connect");
            return;
        }

        int retries = startDtRetries.getValue();
        for (int i = 1; i <= retries; i++) {
            try {
                log("Send STARTDT (try " + i + ")");
                connection.startDataTransfer();
                break;
            }
            catch (InterruptedIOException e) {
                if (i == retries) {
                    connection.close();
                    return;
                }
            }
            catch (IOException e) {
                return;
            }
        }

        log("Successfully connected");

        actionProcessor.addAction(new Action(INTERROGATION_ACTION_KEY, "interrogation C_IC_NA_1"));
        actionProcessor.addAction(new Action(COUNTER_INTERROGATION_ACTION_KEY, "counter interrogation C_CI_NA_1"));
        actionProcessor.addAction(new Action(CLOCK_SYNC_ACTION_KEY, "synchronize clocks C_CS_NA_1"));
        actionProcessor.addAction(new Action(SINGLE_COMMAND_SELECT, "single command SELECT (SBO)"));
        actionProcessor.addAction(new Action(SINGLE_COMMAND_EXECUTE, "single command EXECUTE (SBO)"));
        actionProcessor.addAction(new Action(SEND_STOPDT, "STOPDT act"));
        actionProcessor.addAction(new Action(SEND_STARTDT, "STARTDT act"));

        actionProcessor.start();
    }

    private static void log(String... strings) {
        String time = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS ").format(new Date());
        StringBuilder sb = new StringBuilder(time);
        for (String s : strings) sb.append(s);
        System.out.println(sb.toString());
    }
}
