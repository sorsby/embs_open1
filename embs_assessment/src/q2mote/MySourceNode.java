package q2mote;

import com.ibm.saguaro.system.*;
import q2.SourceNode;

/**
 * A Source node implemented with MoteRunner
 * <p>
 * Yellow LED = Source has initialized (always on)
 * Green LED  = Blinked when a beacon is received
 * Red LED    = Blinked when a packet is transmitted
 */
public final class MySourceNode {
    private static final byte YELLOW_LED = (byte) 0;
    private static final byte GREEN_LED = (byte) 1;
    private static final byte RED_LED = (byte) 2;

    private static final byte LED_OFF = (byte) 0;
    private static final byte LED_ON = (byte) 1;

    /**
     * Number of channels
     */
    private static final int NUM_CHANNELS = 3;

    /**
     * Number to add to the channel id to get the PAN id
     */
    private static final int PAN_ID_OFFSET = 0x11;

    /**
     * The short address of this source node
     */
    private static final int MY_SHORT_ADDRESS = 0x42;

    /**
     * Constant payload to send
     */
    private static final byte PAYLOAD = 0x11;

    /**
     * The controller for the source node
     */
    private static final SourceNode sourceNode = new SourceNode(NUM_CHANNELS);

    /**
     * Radio reference
     */
    private static final Radio radio = new Radio();

    /**
     * Wakeup timer
     */
    private static final Timer timer = new Timer();

    /**
     * Transmit buffer
     */
    private static final byte[] xmit = new byte[12];

    /**
     * If true, we are currently receiving or transmitting
     */
    private static boolean rxOn, txOn;

    /**
     * Channel to send on first.
     * <p>
     * If we try to send on a channel which we are not currently on (fairly
     * likely), this variable is filled with the channel number so we know what
     * it is after we've finished changing the channel.
     * <p>
     * This variable is -1 if there is no pending send channel (ie ask the
     * controller for one instead).
     */
    private static int pendingSendChannel = -1;

    //static constructor to init the mote source node.
    static {
        radio.open(Radio.DID, null, 0, 0);

        //set callback for the reception phase on the radio.
        radio.setRxHandler(new DevCallback(null) {
            @Override
            public int invoke(int flags, byte[] data, int len, int info, long time) {
                return rxBeacon(flags, data, len, info, time);
            }
        });

        //set callback for the transmission phase on the radio.
        radio.setTxHandler(new DevCallback(null) {
            @Override
            public int invoke(int flags, byte[] data, int len, int info, long time) {
                return txComplete(flags, data, len, info, time);
            }
        });

        //set callback for the timer for reading beacons and firings.
        timer.setCallback(new TimerEvent(null) {
            @Override
            public void invoke(byte param, long time) {
                timerFire(param, time);
            }
        });

        //radio setup
        xmit[0] = Radio.FCF_DATA;
        xmit[1] = Radio.FCA_SRC_SADDR | Radio.FCA_DST_SADDR;
        Util.set16le(xmit, 9, MY_SHORT_ADDRESS);
        xmit[11] = PAYLOAD;
        changeChannel(sourceNode.getCurrentChannel());

        // Enter read mode and enable timer
        handleControllerStateChange();

        // Turn Yellow LED on once we've finished
        LED.setState(YELLOW_LED, LED_ON);
    }

    /**
     * Timer callback
     */
    static void timerFire(byte param, long time) {
        // Send a wakeup event and handle any sends or channel changes
        sourceNode.registerNextFire(Time.currentTime(Time.MILLISECS));
        handleControllerStateChange();
    }

    /**
     * Radio beacon received callback
     */
    static int rxBeacon(int flags, byte[] data, int len, int info, long time) {
        // Handle new beacon packets
        if (data != null) {
            // Validate beacon
            if (len != 12 || (data[0] & Radio.FCF_TYPE) != Radio.FCF_BEACON)
                return 0;

            // Ensure we're receiving a beacon for the right channel
            //  If a packet is sent just before we change channels, we might receive it
            if (Util.get16le(data, 3) != PAN_ID_OFFSET + sourceNode.getCurrentChannel())
                return 0;

            // Indicate we received a beacon
            toggleLED(GREEN_LED);

            // Notify controller
            //  We send a wakeupEvent as well to ensure all pending sends are refreshed now
            long fireTime = Time.currentTime(Time.MILLISECS);
            int n = data[11];

            sourceNode.readBeacon(fireTime, n);
            sourceNode.registerNextFire(fireTime);
        } else {
            // Receiving finished
            rxOn = false;
        }

        // Handle any sends or channel changes
        handleControllerStateChange();
        return 0;
    }

    /**
     * Radio transmit complete callback
     */
    static int txComplete(int flags, byte[] data, int len, int info, long time) {
        txOn = false;
        handleControllerStateChange();
        return 0;
    }

    /**
     * Handles any changes in the controller state (sends and channel hopping)
     */
    private static void handleControllerStateChange() {
        // Reschedule timer event
        timer.cancelAlarm();

        long wakeupTime = sourceNode.getNextFireTime();
        if (wakeupTime > 0)
            timer.setAlarmTime(Time.toTickSpan(Time.MILLISECS, wakeupTime));

        // See if there is anything to transmit
        int sendChannel;
        if (pendingSendChannel != -1) {
            sendChannel = pendingSendChannel;
            pendingSendChannel = -1;
        } else {
            sendChannel = sourceNode.getFireChannel();
        }

        if (sendChannel != -1) {
            // Switch channel and send
            if (tryChangeChannel(sendChannel)) {
                radio.transmit(Device.ASAP | Radio.TXMODE_POWER_MAX | Radio.TXMODE_CCA,
                        xmit, 0, xmit.length, 0);
                toggleLED(RED_LED);
                txOn = true;
            } else {
                // Wait for an rxOff or txOff event
                pendingSendChannel = sendChannel;
            }
        } else {
            // Change channel to read on
            int readChannel = sourceNode.getCurrentChannel();

            if (readChannel != -1) {
                if (tryChangeChannel(readChannel)) {
                    radio.startRx(Device.ASAP | Device.RX4EVER, 0, 0);
                    rxOn = true;
                }
            } else if (rxOn) {
                // Nothing more to read, so switch the radio off
                radio.stopRx();
                rxOn = false;
            }
        }
    }

    /**
     * Try and change the radio channel
     * <p>
     * If the radio is on, it begins the turn off procedure.
     *
     * @param channel channel to change to
     * @return true if the channel was immediately changed
     */
    private static boolean tryChangeChannel(int channel) {
        // Do nothing if we're already on the right channel
        if (radio.getChannel() == channel)
            return true;

        // We can't do anything if the receiver or transmitter are on
        if (rxOn) {
            radio.stopRx();
            return false;
        }

        if (txOn)
            return false;

        changeChannel(channel);
        return true;
    }

    /**
     * Forcefully change the radio channel
     *
     * @param channel channel to change to
     */
    private static void changeChannel(int channel) {
        radio.setState(Device.S_OFF);

        // Change the channel
        radio.setChannel((byte) channel);
        radio.setPanId(PAN_ID_OFFSET + channel, true);

        Util.set16le(xmit, 3, PAN_ID_OFFSET + channel); // Dest PAN
        Util.set16le(xmit, 5, PAN_ID_OFFSET + channel); // Dest Address
        Util.set16le(xmit, 7, PAN_ID_OFFSET + channel); // Source PAN
    }

    /**
     * Toggles the given LED
     */
    private static void toggleLED(byte led) {
        LED.setState(led, (byte) (1 - LED.getState(led)));
    }
}
