package org.stomp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 *
 * @author Rory Slegtenhorst <rory.slegtenhorst@gmail.com>
 *
 */
public class StompFrame implements Stomp {

    private final String mCommand;
    private final Map<String, String> mHeaders;
    private final byte[] mPayload;

    public StompFrame(String command) {
        this(command, null);
    }

    public StompFrame(String command, byte[] payload) {
        this(command, payload, new LinkedHashMap<String, String>());
    }

    public StompFrame(String command, byte[] payload, Map<String, String> headers) {
        mCommand = command;
        mPayload = payload;
        mHeaders = headers;
    }

    public void addHeader(String name, String value) {
        mHeaders.put(name, value);
    }

    public String getCommand() {
        return mCommand;
    }

    public String getContentType() {
        return mHeaders.containsKey(HEADER_CONTENT_TYPE) ? mHeaders.get(HEADER_CONTENT_TYPE) : null;
    }

    public byte[] getPayload() {
        return mPayload;
    }

    public Map<String, String> getHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.putAll(mHeaders);
        return headers;
    }

    @Override
    public String toString() {
        String result = "StompFrame command: " + mCommand + ", ";
        for (Map.Entry<String, String> header : mHeaders.entrySet())
            result += header.getKey() + ":" + header.getValue() + ", ";
        result += "length: " + (mPayload == null ? -1 : mPayload.length);
        return result;
    }

    /**
     * Read a frame from the input stream.
     * @param input
     * @return StompFrame
     * @throws java.io.IOException
     */
    public static StompFrame readFrame(InputStream input) throws IOException {
        if (DEBUG) System.out.println("StompConnection.readFrame +");
        boolean isFinalized = false;
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input));

            // reader.ReadLine is blocking for the duration of mSocket.getReadTimeout() which defaults to 0.
            // When implementing heart-beats, we have to modify the read timeout and throw exceptions when the
            // heart-beat timeout has been reached.
            // Worst of all, if properly implemented, each explicit read statement should be equipped with additional
            // heart-beat detection code.

            String command = "";
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if ("".equals(line)) {
                    // skip any existing empty lines in the buffer

                    //TODO:Update heart-beat mechanism here

                    if (DEBUG) System.out.println("StompConnection.readFrame Skip empty line");
                } else {
                    if (DEBUG) System.out.println("StompConnection.readFrame Command: " + line);

                    if (CONNECTED.equals(line) || MESSAGE.equals(line) || RECEIPT.equals(line) || ERROR.equals(line)) {
                        command = line;
                        break;
                    } else {
                        if (DEBUG) System.out.println("StompConnection.readFrame Skipping invalid command");
                    }
                }
            }

            final Map<String, String> headers = new LinkedHashMap<String, String>();

            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.equals("")) {
                    if (DEBUG) System.out.println("StompConnection.readFrame Break on empty line");
                    break;
                }
                if (DEBUG) System.out.println("StompConnection.readFrame Header: " + line);
                int pos = line.indexOf(":");
                if (pos != -1) {
                    if (DEBUG && VERBOSE) System.out.println("StompConnection.readFrame Found :");
                    String header = line.substring(0, pos);
                    String value = line.substring(pos + 1);
                    if (DEBUG && VERBOSE) System.out.println("StompConnection.readFrame Posting header: " + header + ", value: " + value);
                    headers.put(header, value);
                } else {
                    if (DEBUG && VERBOSE) System.out.println("StompConnection.readFrame Could not find :");
                }
            }

            if (headers.containsKey(HEADER_CONTENT_LENGTH)) {
                if (DEBUG) System.out.println("StompConnection.readFrame Found content-length");

                String sLength = headers.get(HEADER_CONTENT_LENGTH);
                if (DEBUG) System.out.println("StompConnection.readFrame Length: " + sLength);

                Integer length = Integer.parseInt(sLength);
                byte[] payload = new byte[length];
                for (int pos = 0; pos <= length; pos++) {
                    if (pos < length)
                        payload[pos] = (byte) reader.read();
                    else isFinalized = reader.read() == 0; // Any "LINE FEED" character should be picked up by the next StompFrame.read
                }
                if (DEBUG) System.out.println("StompConnection.readFrame Length: " + sLength);
                return new StompFrame(command, payload, headers);
            } else {
                if (DEBUG) System.out.println("StompConnection.readFrame Could not find content-length");

                ByteArrayOutputStream payload = new ByteArrayOutputStream(0);
                for (byte value = (byte) reader.read(); value > 0; value = (byte) reader.read()) { // > 0 catches both EoF and the 0 char
                    payload.write(value);
                }
                isFinalized = true;
                return new StompFrame(command, payload.toByteArray(), headers);
            }
        } finally {
            if (DEBUG) System.out.println("StompConnection.readFrame - " + isFinalized);
        }
    }

    public static void writeFrame(StompFrame frame, OutputStream output) throws IOException {
        if (DEBUG) System.out.println("StompConnection.writeFrame +");
        try {
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output));
            try {
                if (DEBUG) System.out.println("StompConnection.writeFrame Command: " + frame.getCommand());
                if (HEARTBEAT.equals(frame.getCommand())) {
                    writer.write(10);
                    return;
                }
                writer.write(frame.getCommand() + "\n");
                for (Map.Entry<String, String> header : frame.getHeaders().entrySet()) {
                    String value = header.getKey() + ":" + header.getValue();
                    if (DEBUG) System.out.println("StompConnection.writeFrame Header: " + value);
                    writer.write(value + "\n");
                }
                if (DEBUG && VERBOSE) System.out.println("StompConnection.writeFrame LF");
                writer.write("\n");
                if (frame.getPayload() != null) {
                    byte[] payload = frame.getPayload();
                    if (DEBUG) System.out.println("StompConnection.writeFrame Payload: " + payload.length);
                    for (int pos = 0; pos <= payload.length; pos++) {
                        if (pos < payload.length)
                            writer.write(payload[pos]);
                        else
                            writer.write(0);
                    }
                } else writer.write(0);
                if (DEBUG && VERBOSE) System.out.println("StompConnection.writeFrame LF");
                writer.write("\n");
            } finally {
                if (DEBUG && VERBOSE) System.out.println("StompConnection.writeFrame Flush");
                writer.flush();
            }
        } finally {
            if (DEBUG) System.out.println("StompConnection.writeFrame -");
        }
    }


    /*
    private void writeObject(java.io.ObjectOutputStream out)
            throws IOException
    private void readObject(java.io.ObjectInputStream in)
            throws IOException, ClassNotFoundException;
    private void readObjectNoData()
            throws ObjectStreamException;
    */

}