package audio;

import static common.Constants.FFTSIZE;
import static common.Constants.SAMPLE_RATE;
import static common.Utils.bytesToHex;
import fft.Complex;
import static fft.FFT.fft;
import java.util.logging.Logger;
import javax.sound.sampled.LineUnavailableException;
import spectrum.MainController;

public class AudioInThread extends Thread
{

    static final Logger logger = Logger.getLogger(audio.AudioInThread.class.getName());
    public boolean stopRequest = false;

    Audio audio = new Audio();

    public AudioInThread()
    {
        logger.info("Audio in thread is started");

        audio.OpenAudioIn(SAMPLE_RATE);

        try
        {
            audio.targetDataLine.open();
        }
        catch (LineUnavailableException ex)
        {
            logger.severe("LineUnavailableException : " + ex.getMessage());
        }
        audio.targetDataLine.start();

        logger.info("Audio starts : " + audio.targetDataLine.isOpen());

    }

    public void run()
    {

        byte[] audioInByte = new byte[FFTSIZE * 2];
        Complex[] complexIn = new Complex[FFTSIZE];
        //    MainController.complexOut = new Complex[fftSize];

        while (!stopRequest)
        {
            int audioInReadInBytes = audio.targetDataLine.read(audioInByte, 0, FFTSIZE * 2);

            logger.fine("Read : " + audioInReadInBytes + ", dump : " + bytesToHex(audioInByte));

            for (int i = 0; i < audioInReadInBytes; i = i + 2)
            {
                // Java shorts (16 bit) are in Big Endian;
                // audio stream is big endian, highest comes out first
                /*
                short ub1 = (short) (audioInByte[i] & 0xFF);
                short ub2 = (short) (audioInByte[i + 1] & 0xFF);
                //    short left = (short) (gain[0] * ((ub1 << 8) + ub2));
                short mono = (short) (((ub1 << 8) + ub2));
                 */
                //  int mono = (audioInByte[i] << 8) + audioInByte[i + 1];
                short mono = (short) ((audioInByte[i] << 8) | (audioInByte[i + 1] & 0xff));
                logger.fine("Read from audio : " + audioInByte[i + 1] + ", " + audioInByte[i] + ", mono : " + mono);
                complexIn[i / 2] = new Complex(mono, 0);
            }
            MainController.complexOut = fft(complexIn);
        }

        audio.targetDataLine.stop();
        audio.targetDataLine.close();

        logger.info("Exiting AudioInThread");
    }

    public static void delay(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
        }
    }
}
