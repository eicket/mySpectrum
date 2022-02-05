// Erik Icket, ON4PB - 2022
package audio;

import common.Constants;
import static common.Constants.FFTSIZE;
import static common.Constants.SAMPLE_RATE;
import static common.Utils.bytesToHex;
import fft.Complex;
import static fft.FFT.fft;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;
import spectrum.MainController;

public class AudioInThread extends Thread
{

    static final Logger logger = Logger.getLogger(audio.AudioInThread.class.getName());
    public boolean stopRequest = false;

    private int nBitsPerSample = 16;
    private boolean bBigEndian = true;
    private int nChannels = 1;
    private int nFrameSize = (nBitsPerSample / 8) * nChannels;
    private AudioFormat.Encoding encoding = AudioFormat.Encoding.PCM_SIGNED;

    private TargetDataLine targetDataLine;
    private AudioFormat audioFormat;

    public AudioInThread(String device)
    {
        logger.info("Audio in thread is started");

        // find all audio mixers       
        Mixer.Info[] aInfos = AudioSystem.getMixerInfo();

        boolean foundAudioIn = false;

        for (int i = 0; i < aInfos.length; i++)
        {
            Mixer mixer = AudioSystem.getMixer(aInfos[i]);
            logger.fine("next mixer : " + mixer.getMixerInfo().toString());

            // test for a target == audio in
            if (mixer.isLineSupported(new Line.Info(TargetDataLine.class)))
            {
                logger.fine("audio in - target : " + mixer.getMixerInfo().getName());

                if ((mixer.getMixerInfo().getName()).equalsIgnoreCase(device))
                {
                    logger.info("Found received AudioIn device : " + device);

                    foundAudioIn = true;
                    audioFormat = new AudioFormat(encoding, Constants.SAMPLE_RATE, nBitsPerSample, nChannels, nFrameSize, Constants.SAMPLE_RATE, bBigEndian);

                    try
                    {
                        // Obtains a target data line that can be used for recording audio data 
                        targetDataLine = (TargetDataLine) AudioSystem.getTargetDataLine(audioFormat, mixer.getMixerInfo());
                        logger.info("Target data line created");
                    }
                    catch (Exception ex)
                    {
                        logger.severe("Can't get TargetDataLine : " + ex.getMessage());
                    }

                    break;
                }
            }

        }
        if (!foundAudioIn)
        {
            logger.info("No Audio In found");
            return;
        }

        try
        {
            targetDataLine.open(audioFormat);
        }
        catch (LineUnavailableException ex)
        {
            logger.severe("LineUnavailableException : " + ex.getMessage());
        }
        targetDataLine.start();

        logger.info("Audio starts : " + targetDataLine.isOpen() + ", freq resolution : " + (float) SAMPLE_RATE / FFTSIZE + "hz, sample period : " + (float) FFTSIZE / SAMPLE_RATE + " secs");
    }

    public void run()
    {

        byte[] audioInByte = new byte[FFTSIZE * 2];
        Complex[] complexIn = new Complex[FFTSIZE];

        while (!stopRequest)
        {
            int audioInReadInBytes = targetDataLine.read(audioInByte, 0, FFTSIZE * 2);

            logger.fine("Read : " + audioInReadInBytes + ", dump : " + bytesToHex(audioInByte));

            for (int i = 0; i < audioInReadInBytes; i = i + 2)
            {
                // Java shorts (16 bit) are in Big Endian;
                // audio stream is big endian, highest comes out first

                short mono = (short) ((audioInByte[i] << 8) | (audioInByte[i + 1] & 0xff));
                logger.fine("Read from audio : " + audioInByte[i + 1] + ", " + audioInByte[i] + ", mono : " + mono);
                complexIn[i / 2] = new Complex(mono, 0);
            }
            MainController.complexOut = fft(complexIn);
            MainController.newBuffer = true;
        }

        targetDataLine.stop();
        targetDataLine.close();

        logger.info("Exiting AudioInThread");
    }
}
