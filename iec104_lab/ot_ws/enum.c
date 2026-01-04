/*
 * Author: 4p0cryph0n
 *
 * This file is part of an educational OT/ICS laboratory for studying
 * IEC 60870-5-104 attack vectors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is provided for educational and defensive security
 * research purposes only.
 */


#include "cs104_connection.h"
#include "hal_thread.h"
#include "hal_time.h"

#include <stdio.h>
#include <stdlib.h>

static int running = 1;

static void
connectionHandler(void* parameter, CS104_Connection connection, CS104_ConnectionEvent event)
{
    switch (event) {
        case CS104_CONNECTION_OPENED:
            printf("[+] Connection established\n");
            break;
        case CS104_CONNECTION_CLOSED:
            printf("[-] Connection closed\n");
            running = 0;
            break;
        case CS104_CONNECTION_FAILED:
            printf("[-] Connection failed\n");
            running = 0;
            break;
        default:
            break;
    }
}

static bool
asduHandler(void* parameter, int address, CS101_ASDU asdu)
{
    TypeID type = CS101_ASDU_getTypeID(asdu);

    printf("[>] Received ASDU: Type=%s (%d), Elements=%d\n",
        TypeID_toString(type),
        type,
        CS101_ASDU_getNumberOfElements(asdu));
        
    if (type == C_IC_NA_1) {
        CS101_CauseOfTransmission cot = CS101_ASDU_getCOT(asdu);

        if (cot == CS101_COT_ACTIVATION_CON)
            printf("    [GI Activation Confirmation]\n");
        else if (cot == CS101_COT_ACTIVATION_TERMINATION)
            printf("    [GI Termination]\n");
        else
            printf("    [GI Other COT: %d]\n", cot);

        return true;  // Skip IOA printing for GI
    }

    switch (type) {

        case M_SP_NA_1: // Single point
            for (int i = 0; i < CS101_ASDU_getNumberOfElements(asdu); i++) {
                SinglePointInformation spi = (SinglePointInformation) CS101_ASDU_getElement(asdu, i);
                printf("    IOA: %d | Type: M_SP_NA_1 | Value: %d\n",
                    InformationObject_getObjectAddress((InformationObject) spi),
                    SinglePointInformation_getValue(spi));
                InformationObject_destroy((InformationObject) spi);
            }
            break;

        case M_DP_NA_1: // Double point
            for (int i = 0; i < CS101_ASDU_getNumberOfElements(asdu); i++) {
                DoublePointInformation dpi = (DoublePointInformation) CS101_ASDU_getElement(asdu, i);
                printf("    IOA: %d | Type: M_DP_NA_1 | Value: %d\n",
                    InformationObject_getObjectAddress((InformationObject) dpi),
                    DoublePointInformation_getValue(dpi));
                InformationObject_destroy((InformationObject) dpi);
            }
            break;

        case M_ME_NB_1: // Scaled measured value
            for (int i = 0; i < CS101_ASDU_getNumberOfElements(asdu); i++) {
                MeasuredValueScaled mvs = (MeasuredValueScaled) CS101_ASDU_getElement(asdu, i);
                printf("    IOA: %d | Type: M_ME_NB_1 | Value: %d\n",
                    InformationObject_getObjectAddress((InformationObject) mvs),
                    MeasuredValueScaled_getValue(mvs));
                InformationObject_destroy((InformationObject) mvs);
            }
            break;

        case M_ME_NC_1: // Short float measured value
            for (int i = 0; i < CS101_ASDU_getNumberOfElements(asdu); i++) {
                MeasuredValueShort mvs = (MeasuredValueShort) CS101_ASDU_getElement(asdu, i);
                printf("    IOA: %d | Type: M_ME_NC_1 | Value: %.2f\n",
                    InformationObject_getObjectAddress((InformationObject) mvs),
                    MeasuredValueShort_getValue(mvs));
                InformationObject_destroy((InformationObject) mvs);
            }
            break;

        default:
            printf("    [Unsupported ASDU type: %d]\n", type);
            break;
    }

    return true;
}



int main(void)
{
    const char* ip = "127.0.0.1";
    int port = 2404;
    int asdu = 65535;

    printf("[*] Connecting to %s:%d (ASDU %d)\n", ip, port, asdu);

    CS104_Connection con = CS104_Connection_create(ip, port);

    CS104_Connection_setConnectionHandler(con, connectionHandler, NULL);
    CS104_Connection_setASDUReceivedHandler(con, asduHandler, NULL);

    if (CS104_Connection_connect(con)) {
        CS104_Connection_sendStartDT(con);

        Thread_sleep(500);

        printf("[>] Sending general interrogation (C_IC_NA_1)...\n");
        CS104_Connection_sendInterrogationCommand(con, CS101_COT_ACTIVATION, asdu, IEC60870_QOI_STATION);

        Thread_sleep(5000);
    } else {
        printf("[-] Failed to connect to target\n");
    }

    CS104_Connection_destroy(con);
    return 0;
}
