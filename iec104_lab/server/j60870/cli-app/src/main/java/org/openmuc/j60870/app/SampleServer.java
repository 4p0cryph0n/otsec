/*
 * MODIFICATION NOTICE
 * -------------------
 *
 * This file is a modified version of the original SampleServer provided by
 * the j60870 project (Fraunhofer ISE / OpenMUC). This file has been modified by 4p0cryph0n.
 *
 * Modifications have been made for the purpose of:
 *   - Simulating a realistic IEC 60870-5-104 outstation (RTU)
 *   - Implementing Select-Before-Operate (SBO) control logic
 *   - Modeling breaker state changes and spontaneous indications (M_SP_NA_1)
 *   - Supporting hands-on learning and defensive security research
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

import org.openmuc.j60870.*;
import org.openmuc.j60870.Server.Builder;
import org.openmuc.j60870.ie.*;
import org.openmuc.j60870.internal.cli.*;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SampleServer {

    private static final StringCliParameter bindAddressParam = new CliParameterBuilder("-a")
            .setDescription("The bind address.")
            .buildStringParameter("address", "127.0.0.1");

    private static final IntCliParameter portParam = new CliParameterBuilder("-p")
            .setDescription("The port listen on.")
            .buildIntParameter("port", 2404);

    private static final IntCliParameter iaoLengthParam = new CliParameterBuilder("-iaol")
            .setDescription("Information Object Address (IOA) field length.")
            .buildIntParameter("iao_length", 3);

    private static final IntCliParameter cotLengthParam = new CliParameterBuilder("-cotl")
            .setDescription("Cause Of Transmission (CoT) field length.")
            .buildIntParameter("cot_length", 2);

    private static final IntCliParameter caLengthParam = new CliParameterBuilder("-cal")
            .setDescription("Common Address (CA) field length.")
            .buildIntParameter("ca_length", 2);

    private int connectionIdCounter = 1;

    // IOA -> breaker CLOSED(true) / OPEN(false)
    private static final Map<Integer, Boolean> breakers = new ConcurrentHashMap<>();

    // Active connections (for spontaneous updates)
    private static final Set<Connection> activeConnections = ConcurrentHashMap.newKeySet();

    // Last CA seen (used for spontaneous)
    private static volatile int lastSeenCommonAddress = 1;

    static {
        breakers.put(1001, true);
        breakers.put(1002, true);
        breakers.put(1003, false);
    }

    public static void main(String[] args) throws UnknownHostException {
        cliParser(args);
        new SampleServer().start();
    }

    private static void cliParser(String[] args) {
        List<CliParameter> cliParameters = new ArrayList<>();
        cliParameters.add(bindAddressParam);
        cliParameters.add(portParam);
        cliParameters.add(iaoLengthParam);
        cliParameters.add(caLengthParam);
        cliParameters.add(cotLengthParam);

        CliParser cliParser = new CliParser(
                "j60870-sample-server",
                "IEC 60870-5-104 sample outstation with realistic control behavior"
        );

        cliParser.addParameters(cliParameters);
        try {
            cliParser.parseArguments(args);
        } catch (CliParseException e) {
            System.err.println("Error parsing parameters: " + e.getMessage());
            System.out.println(cliParser.getUsageString());
            System.exit(1);
        }
    }

    public void start() throws UnknownHostException {
        log("### Starting IEC-104 Outstation ###\n",
                "Bind Address: ", bindAddressParam.getValue(), "\n",
                "Port: ", String.valueOf(portParam.getValue()), "\n");

        Builder builder = Server.builder();
        builder.setBindAddr(InetAddress.getByName(bindAddressParam.getValue()))
                .setPort(portParam.getValue())
                .setIoaFieldLength(iaoLengthParam.getValue())
                .setCommonAddressFieldLength(caLengthParam.getValue())
                .setCotFieldLength(cotLengthParam.getValue());

        Server server = builder.build();

        try {
            server.start(new ServerListener());
            startBreakerConsole();
            log("Breaker console ready: show | toggle <ioa> | set <ioa> <0|1>");
        } catch (IOException e) {
            log("Unable to start server: ", e.getMessage());
        }
    }

    private void log(String... msg) {
        String time = new SimpleDateFormat("yyyy.MM.dd HH:mm:ss.SSS ").format(new Date());
        StringBuilder sb = new StringBuilder(time);
        for (String s : msg) sb.append(s);
        System.out.println(sb);
    }

    // ---------- ASDU helpers ----------

    private static ASdu buildBreakerGIAsdu(int ca) {
        List<InformationObject> ios = new ArrayList<>();
        for (Map.Entry<Integer, Boolean> e : breakers.entrySet()) {
            ios.add(new InformationObject(
                    e.getKey(),
                    new InformationElement[][]{
                            {new IeSinglePointWithQuality(e.getValue(), false, false, false, false)}
                    }
            ));
        }

        return new ASdu(
                ASduType.M_SP_NA_1,
                false,
                CauseOfTransmission.INTERROGATED_BY_STATION,
                false, false, 0, ca,
                ios.toArray(new InformationObject[0])
        );
    }

    private static ASdu buildSpontaneousAsdu(int ca, int ioa, boolean val) {
        return new ASdu(
                ASduType.M_SP_NA_1,
                false,
                CauseOfTransmission.SPONTANEOUS,
                false, false, 0, ca,
                new InformationObject(
                        ioa,
                        new InformationElement[][]{
                                {new IeSinglePointWithQuality(val, false, false, false, false)}
                        }
                )
        );
    }

    private static void broadcastBreakerUpdate(int ca, int ioa, boolean val) {
        ASdu update = buildSpontaneousAsdu(ca, ioa, val);
        for (Connection c : activeConnections) {
            try {
                c.send(update);
            } catch (IOException ignored) {
            }
        }
    }

    // ---------- Console ----------

    private static void startBreakerConsole() {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
                while (true) {
                    String line = br.readLine();
                    if (line == null) return;
                    String[] p = line.trim().split("\\s+");
                    if (p.length == 0) continue;

                    switch (p[0]) {
                        case "show":
                            System.out.println("Breakers: " + breakers);
                            break;
                        case "toggle":
                            int ioa = Integer.parseInt(p[1]);
                            boolean nv = !breakers.get(ioa);
                            breakers.put(ioa, nv);
                            broadcastBreakerUpdate(lastSeenCommonAddress, ioa, nv);
                            break;
                        case "set":
                            ioa = Integer.parseInt(p[1]);
                            boolean v = "1".equals(p[2]);
                            breakers.put(ioa, v);
                            broadcastBreakerUpdate(lastSeenCommonAddress, ioa, v);
                            break;
                    }
                }
            } catch (Exception e) {
                System.err.println("Console error: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    // ---------- Server logic ----------

    public class ServerListener implements ServerEventListener {

        @Override
        public ConnectionEventListener connectionIndication(Connection connection) {
            activeConnections.add(connection);
            int id = connectionIdCounter++;
            log("Client connected (ID ", String.valueOf(id), ")");
            return new ConnectionListener(connection, id);
        }

        @Override
        public void serverStoppedListeningIndication(IOException e) {
            log("Server stopped: ", e.getMessage());
        }

        @Override
        public void connectionAttemptFailed(IOException e) {
            log("Connection attempt failed: ", e.getMessage());
        }

        public class ConnectionListener implements ConnectionEventListener {

            private final Connection connection;
            private final int id;

            // IOA -> selected desired state
            private final Map<Integer, Boolean> selectedIoas = new ConcurrentHashMap<>();

            public ConnectionListener(Connection connection, int id) {
                this.connection = connection;
                this.id = id;
            }

            @Override
            public void newASdu(Connection connection, ASdu aSdu) {
                lastSeenCommonAddress = aSdu.getCommonAddress();

                try {
                    switch (aSdu.getTypeIdentification()) {

                        case C_IC_NA_1:
                            connection.sendConfirmation(aSdu);
                            connection.send(buildBreakerGIAsdu(aSdu.getCommonAddress()));
                            connection.sendActivationTermination(aSdu);
                            break;

                        case C_SC_NA_1:
                            InformationObject io = aSdu.getInformationObjects()[0];
                            IeSingleCommand cmd =
                                    (IeSingleCommand) io.getInformationElements()[0][0];

                            int ioa = io.getInformationObjectAddress();
                            boolean desired = cmd.isCommandStateOn();

                            if (!breakers.containsKey(ioa)) {
                                connection.sendConfirmation(
                                        aSdu,
                                        aSdu.getCommonAddress(),
                                        true,
                                        CauseOfTransmission.UNKNOWN_INFORMATION_OBJECT_ADDRESS
                                );
                                break;
                            }

                            if (cmd.isSelect()) {
                                selectedIoas.put(ioa, desired);
                                connection.sendConfirmation(aSdu);
                                break;
                            }

                            // EXECUTE
                            Boolean sel = selectedIoas.get(ioa);
                            if (sel == null || sel != desired) {
                                connection.sendConfirmation(aSdu);
                                break;
                            }

                            selectedIoas.remove(ioa);
                            breakers.put(ioa, desired);
                            broadcastBreakerUpdate(lastSeenCommonAddress, ioa, desired);
                            connection.sendConfirmation(aSdu);
                            break;

                        default:
                            connection.sendConfirmation(
                                    aSdu,
                                    aSdu.getCommonAddress(),
                                    true,
                                    CauseOfTransmission.UNKNOWN_TYPE_ID
                            );
                    }

                } catch (IOException e) {
                    log("Connection ", String.valueOf(id), " closed");
                }
            }

            @Override
            public void connectionClosed(Connection connection, IOException e) {
                activeConnections.remove(connection);
                selectedIoas.clear();
            }

            @Override
            public void dataTransferStateChanged(Connection connection, boolean stopped) {
            }
        }
    }
}
