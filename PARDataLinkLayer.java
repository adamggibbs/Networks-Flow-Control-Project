// =============================================================================
// IMPORTS

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Queue;

import java.time.Instant;
// =============================================================================


// =============================================================================
/**
 * @file   PARDataLinkLayer.java
 * @author Adam Gibbs & Nathanial Crosby
 * @date   March 2020
 *
 * A data link layer that uses start/stop tags and byte packing to frame the
 * data, and that performs error management with a parity bit.  It employs
 * flow control.
 */
public class PARDataLinkLayer extends DataLinkLayer {
// =============================================================================


 
    // =========================================================================
    /**
     * Embed a raw sequence of bytes into a framed sequence.
     *
     * @param  data The raw sequence of bytes to be framed.
     * @return A complete frame.
     */
    protected Queue<Byte> createFrame (Queue<Byte> data) {

	// Calculate the parity.
	byte parity = calculateParity(data);
	
	// Begin with the start tag.
	Queue<Byte> framingData = new LinkedList<Byte>();
	framingData.add(startTag);

	// Add each byte of original data.
        for (byte currentByte : data) {

	    // If the current data byte is itself a metadata tag, then precede
	    // it with an escape tag.
	    if ((currentByte == startTag) ||
		(currentByte == stopTag) ||
        (currentByte == escapeTag) ||
        (currentByte == ackTag)) {

		framingData.add(escapeTag);

	    }

	    // Add the data byte itself.
	    framingData.add(currentByte);

	}

	// Add the parity byte.
    framingData.add(parity);

    // Add a frame number to the frame
    // if frame 1 was spent last, send frame 0; if frame 0 was sent last, send frame1
    if(sentData1){
        framingData.add(zeroTag);
        sentData0 = true;
        sentData1 = false;
    } else if(sentData0){
        framingData.add(oneTag);
        sentData0 = false;
        sentData1 = true;
    }
	
	// End with a stop tag.
	framingData.add(stopTag);

	return framingData;
	
    } // createFrame ()
    // =========================================================================


    
    // =========================================================================
    /**
     * Determine whether the received, buffered data constitutes a complete
     * frame.  If so, then remove the framing metadata and return the original
     * data.  Note that any data preceding an escaped start tag is assumed to be
     * part of a damaged frame, and is thus discarded.
     *
     * @return If the buffer contains a complete frame, the extracted, original
     * data; <code>null</code> otherwise.
     */
    protected Queue<Byte> processFrame () {

	// Search for a start tag.  Discard anything prior to it.
	boolean        startTagFound = false;
	Iterator<Byte>             i = receiveBuffer.iterator();
	while (!startTagFound && i.hasNext()) {
	    byte current = i.next();
	    if (current != startTag) {
		i.remove();
	    } else {
		startTagFound = true;
	    }
	}

	// If there is no start tag, then there is no frame.
	if (!startTagFound) {
	    return null;
	}
	
	// Try to extract data while waiting for an unescaped stop tag.
    int                       index = 1;
	LinkedList<Byte> extractedBytes = new LinkedList<Byte>();
	boolean            stopTagFound = false;
	while (!stopTagFound && i.hasNext()) {

	    // Grab the next byte.  If it is...
	    //   (a) An escape tag: Skip over it and grab what follows as
	    //                      literal data.
	    //   (b) A stop tag:    Remove all processed bytes from the buffer and
	    //                      end extraction.
	    //   (c) A start tag:   All that precedes is damaged, so remove it
	    //                      from the buffer and restart extraction.
	    //   (d) Otherwise:     Take it as literal data.
	    byte current = i.next();
            index += 1;
	    if (current == escapeTag) {
		if (i.hasNext()) {
		    current = i.next();
                    index += 1;
		    extractedBytes.add(current);
		} else {
		    // An escape was the last byte available, so this is not a
		    // complete frame.
		    return null;
		}
	    } else if (current == stopTag) {
            cleanBufferUpTo(index);
            stopTagFound = true;
	    } else if (current == startTag) {
		    cleanBufferUpTo(index - 1);
            index = 1;
		    extractedBytes = new LinkedList<Byte>();
	    } else {
		    extractedBytes.add(current);
	    }

    }

	// If there is no stop tag, then the frame is incomplete.
	if (!stopTagFound) {
	    return null;
	}

	if (debug) {
	    System.out.println("ParityDataLinkLayer.processFrame(): Got whole frame!");
	}
        
    // The final byte inside the frame is the frame number. Remove it and store it.
    byte dataFrameNum = extractedBytes.remove(extractedBytes.size() - 1);
	// The final byte, after the frame number byte is removed, inside the frame is the parity.  Compare it to a recalculation.
	byte receivedParity   = extractedBytes.remove(extractedBytes.size() - 1);
	byte calculatedParity = calculateParity(extractedBytes);
	if (receivedParity != calculatedParity) {
	    System.out.printf("ParityDataLinkLayer.processFrame():\tDamaged frame\n");
	    return null;
	}

	// Add the frame number back after calculations to use in sendAck()
	extractedBytes.addFirst(dataFrameNum);

	return extractedBytes;

    } // processFrame ()
    // =========================================================================



    // =========================================================================
    /**
     * After sending a frame, do any bookkeeping (e.g., buffer the frame in case
     * a resend is required).
     *
     * @param frame The framed data that was transmitted.
     */ 
    protected void finishFrameSend (Queue<Byte> frame) {

        // Check to see which frame number was sent
        /** Then:
         *  store the frame in case it needs to be retransmitted
         *  store the time the frame was sent in an Instant object
         *  place the DLL in a state of waiting for an acknowledgement of that frame number
         */
        if(sentData0){
            data0 = frame;
            sendTime = Instant.now();
            isWaitingAck0 = true;
        } else if(sentData1){
            data1 = frame;
            sendTime = Instant.now();
            isWaitingAck1 = true;
        }
        
    } // finishFrameSend ()
    // =========================================================================



    // =========================================================================
    /**
     * After receiving a frame, do any bookkeeping (e.g., deliver the frame to
     * the client, if appropriate) and responding (e.g., send an
     * acknowledgment).
     *
     * @param frame The frame of bytes received.
     */
    protected void finishFrameReceive (Queue<Byte> frame) {

        // Remove the first byte of the frame which contains the frame number
        byte dataFrameNum = frame.remove();

        // Analyze the frame. If...
	    //   (a) it carries an acknowledgement tag: 
	    //          set the proper isWaitingAck# Boolean variable to false
	    //   (b & c) the frame number doesn't match the expected frame number, we assume the acknowledgment frame was lost:    
	    //          resend the previous acknowledgment frame               
	    //   (c) otherwise assume it is regular data:  
	    //          send an acknowledgement frame and pass data to the client                    
        if(frame.peek() == ackTag){ 
            if(dataFrameNum == 0){
                System.out.println("Receiving Ack" + (int)dataFrameNum);
                isWaitingAck0 = false;
            } else if(dataFrameNum == 1){
                System.out.println("Receiving Ack" + (int)dataFrameNum);
                isWaitingAck1 = false;
            }
        } else if(expectingData0 && dataFrameNum == 1){
            System.out.println("Received unexpected Data Frame Number");
            sendAck((byte)1);
        } else if(expectingData1 && dataFrameNum == 0){
            System.out.println("Received unexpected Data Frame Number");
            sendAck((byte)0);
        } else {
            sendAck(dataFrameNum);
            System.out.println("Sending Ack" + (int)dataFrameNum);
            
            // Deliver frame to the client.
            byte[] deliverable = new byte[frame.size()];
            for (int i = 0; i < deliverable.length; i += 1) {
                deliverable[i] = frame.remove();
            }

            client.receive(deliverable);
        }
        
    } // finishFrameReceive ()
    // =========================================================================



    // =========================================================================
    /**
     * Determine whether a timeout should occur and be processed.  This method
     * is called regularly in the event loop, and should check whether too much
     * time has passed since some kind of response is expected.
     */
    protected void checkTimeout () {

        /** Check timeout by...
         *  Seeing if there is a defined sendTime to compare,
         *  Subtract the timeout duration from the current time
         *  Check if that difference is after the sendTime of the frame
         *  If true, retransmit the frame and do the following:
         *      -reset sendTime to the time of the retransmission
         *      -retransmit the data
         *      -call finishFrameSend(data) to complete the retransmission
         */
        if(sendTime != null && Instant.now().minusSeconds(timeoutTime).isAfter(sendTime)){
            //retransmit
            if(isWaitingAck0){
                System.out.println("RETRANSMIT!");
                sendTime = Instant.now();
                transmit(data0);
                finishFrameSend(data0);
            } else if(isWaitingAck1){
                System.out.println("RETRANSMIT!");
                sendTime = Instant.now();
                transmit(data1);
                finishFrameSend(data1);
            }
        }

    } // checkTimeout ()
    // =========================================================================



    //==========================================================================
    /**
     * Send an acknowledgement of either data frame 0 or 1 depending on what
     * dataFrameNum equals
     */
    private void sendAck (byte dataFrameNum) {

        Queue<Byte> data = new LinkedList<Byte>();
        data.add(ackTag);
        data.add(dataFrameNum);
        Queue<Byte> framedData = createFrame(data);
        transmit(framedData);
        if(dataFrameNum == 0){
            expectingData0 = false;
            expectingData1 = true;
        } else {
            expectingData0 = true;
            expectingData1 = false;
        }

    }
    //==========================================================================



    // =========================================================================
    /**
     * Extract the next frame-worth of data from the sending buffer, frame it,
     * and then send it.
     *
     * @return the frame of bytes transmitted.
     */
    @Override
    protected Queue<Byte> sendNextFrame () {
        
        // If the buffer is empty, or we are waiting on an acknowledgement,
        // Don't send anything
        if (sendBuffer.isEmpty() || isWaitingAck0 || isWaitingAck1) {
            return null;
        }
        
        // Extract a frame-worth of data from the sending buffer.
        int frameSize = ((sendBuffer.size() < MAX_FRAME_SIZE)
                ? sendBuffer.size()
                : MAX_FRAME_SIZE);
        Queue<Byte> data = new LinkedList<Byte>();
        for (int j = 0; j < frameSize; j += 1) {
            data.add(sendBuffer.remove());
        }

        // Create a frame from the data and transmit it.
        Queue<Byte> framedData = createFrame(data);
        transmit(framedData);

        return framedData;

    } // sendNextFrame ()
    // =========================================================================



    // =========================================================================
    /**
     * For a sequence of bytes, determine its parity.
     *
     * @param data The sequence of bytes over which to calculate.
     * @return <code>1</code> if the parity is odd; <code>0</code> if the parity
     *         is even.
     */
    private byte calculateParity (Queue<Byte> data) {

	int parity = 0;
	for (byte b : data) {
	    for (int j = 0; j < Byte.SIZE; j += 1) {
		if (((1 << j) & b) != 0) {
		    parity ^= 1;
		}
	    }
	}

	return (byte)parity;
	
    } // calculateParity ()
    // =========================================================================
    


    // =========================================================================
    /**
     * Remove a leading number of elements from the receive buffer.
     *
     * @param index The index of the position up to which the bytes are to be
     *              removed.
     */
    private void cleanBufferUpTo (int index) {

        for (int i = 0; i < index; i += 1) {
            receiveBuffer.remove();
	}

    } // cleanBufferUpTo ()
    // =========================================================================



    // =========================================================================
    // DATA MEMBERS

    /** The start tag. */
    private final byte startTag  = (byte)'{';

    /** The stop tag. */
    private final byte stopTag   = (byte)'}';

    /** The ack frame */
    private final byte ackTag    = (byte)'~';

    /** The escape tag. */
    private final byte escapeTag = (byte)'\\';

    /** The zero tag  */
    private final byte zeroTag   = (byte) 0;

    /** The one tag. */
    private final byte oneTag    = (byte) 1;

    /** The timeout time (in seconds) */
    private final int timeoutTime = 2;

    /** The send time instant*/
    private Instant sendTime;

    /** The temporarily stored sent data */
    private Queue<Byte> data0;
    private Queue<Byte> data1;

    /** The previously sent frame number */
    private Boolean sentData0 = false;
    private Boolean sentData1 = true;

    /** If DLL is waiting for an acknowledgement of a certain frame number*/
    private Boolean isWaitingAck0 = false;
    private Boolean isWaitingAck1 = false;

    /** Which frame number the DLL is expecting */
    private Boolean expectingData0 = true;
    private Boolean expectingData1 = false;

    // =========================================================================




// =============================================================================
} // class ParityDataLinkLayer
// =============================================================================
