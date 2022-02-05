package audio;

import fft.Complex;
import filter.BandPassFilter;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Logger;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

public class AudioInThread extends Thread
{

    boolean stopRequest = false;
    private static final Logger logger = Logger.getLogger(audio.AudioInThread.class.getName());
    private float sampleRate;
    private int fftSize;
    private int[] gain;
    private int[] requestedFilterFrequency;
    private int[] requestedFilterWidth;
    final int STRAIGHT = 0;
    final int SWAPPED = 1;
    private int[] swapState;
    final int LSB = 0;
    final int USB = 1;
    final int AM = 2;
    final int IPlusQ = 3;
    final int IMinusQ = 4;
    private int[] requestedDemodulation;
    LinkedBlockingDeque lbdReceivedBasebandFFT;
    LinkedBlockingDeque lbdReceivedAudioToSoundcard;
    private TargetDataLine targetDataLine;
    private int runningFilterFrequency;
    private int runningFilterWidth;
    private int runningDemodulation;

    public AudioInThread(String mixerName, float sampleRate, int fftSize, int[] gain, int[] filterFrequency, int[] filterWidth, int[] swapState, int[] demodulation,
            LinkedBlockingDeque lbdReceivedBasebandFFT, LinkedBlockingDeque lbdReceivedAudioToSoundcard)
    {
        this.sampleRate = sampleRate;
        this.fftSize = fftSize;
        this.gain = gain;
        this.requestedFilterFrequency = filterFrequency;
        runningFilterFrequency = -1;
        this.requestedFilterWidth = filterWidth;
        runningFilterWidth = -1;
        this.swapState = swapState;
        this.requestedDemodulation = demodulation;
        runningDemodulation = -1;
        this.lbdReceivedBasebandFFT = lbdReceivedBasebandFFT;
        this.lbdReceivedAudioToSoundcard = lbdReceivedAudioToSoundcard;
        lbdReceivedBasebandFFT.clear();
        lbdReceivedAudioToSoundcard.clear();

        // default : PCM_SIGNED 44100.0 Hz, 16 bit, stereo, 4 bytes/frame, little-endian
        AudioFormat.Encoding encoding = AudioFormat.Encoding.PCM_SIGNED;

        int nBitsPerSample = 16;
        boolean bBigEndian = true;
        int nChannels = 2;
        int nFrameSize = (nBitsPerSample / 8) * nChannels;

        AudioFormat audioFormat = new AudioFormat(encoding, sampleRate, nBitsPerSample, nChannels, nFrameSize, sampleRate, bBigEndian);
        logger.info("Mixer : " + mixerName + ", target audio format: " + audioFormat);

        // int nInternalBufferSize = AudioSystem.NOT_SPECIFIED;
        int nInternalBufferSize = 8 * 8192;
        try
        {
        ///////////////////////////////    targetDataLine = (TargetDataLine) AudioCommon.getTargetDataLine(mixerName, audioFormat, nInternalBufferSize);
                             
        }
        // catch (LineUnavailableException ex)
        catch (Exception ex)
        {
            logger.severe("Can't get TargetDataLine");
        }
        //
        if (targetDataLine == null)
        {
            logger.severe("Can't get TargetDataLine");
        }
        //

        logger.fine("Target data line created : " + targetDataLine.toString());
        targetDataLine.start();

        logger.fine("AudioAnalyzer created, sample rate  : " + sampleRate + ", fftSize : " + fftSize);
    }

    public void run()
    {
        byte[] audioInByte = new byte[fftSize * 4];
        int[][] receivedAudio = new int[fftSize][2];
        // the audio fftIn and fftOut
        Complex[] audioFFTIn = new Complex[fftSize * 2]; // second half is padded with zeroes
        Complex[] audioFFTOut = null;
        Complex[] swappedAudioFFTOut = new Complex[fftSize * 2];
        int[] receivedBasebandFFT = new int[fftSize * 2];
        // the filter FFTIn and FFTOut
        double[] filter = new double[fftSize * 2 + 1]; // double length to remove the image, plus padding
        Complex[] filterFFTIn = new Complex[fftSize * 2 * 2];// second half is padded with zeroes, afterwards we throw half of the spectrum away because of the image 
        Complex[] filterFFTOut = new Complex[fftSize * 2];
        Complex[] tempFilterFFTOut = null;
        // the convolve output
        //Complex[] ConvolvedInFreqDomain = new Complex[fftSize];
        Complex[] ConvolvedInTimeDomain = null; // temp !!!!!!!!!!!!!!!!!
        // the overlap buffer
        Complex[] overlap = new Complex[fftSize];
        for (int i = 0; i < overlap.length; i++)
        {
            overlap[i] = new Complex(0, 0);
        }
        int[] receivedAudioToSoundcard = new int[fftSize];

        int calculatedFilterFrequency = 0;
        int calculatedFilterWidth = 0;
        int calculatedFilterFrequencyBin = 0;
        int calculatedFilterWidthBin = 0;

        while (!stopRequest)
        {
            // read the audio
            int audioInReadInBytes = targetDataLine.read(audioInByte, 0, fftSize * 4);

            for (int i = 0; i < audioInReadInBytes; i = i + 4)
            {
                short ub1 = (short) (audioInByte[i] & 0xFF);
                short ub2 = (short) (audioInByte[i + 1] & 0xFF);
                short left = (short) (gain[0] * ((ub1 << 8) + ub2));

                short ub3 = (short) (audioInByte[i + 2] & 0xFF);
                short ub4 = (short) (audioInByte[i + 3] & 0xFF);
                short right = (short) (gain[0] * ((ub3 << 8) + ub4));

                if (swapState[0] == STRAIGHT)
                {
                    receivedAudio[i / 4][0] = left;
                    receivedAudio[i / 4][1] = right;
                }
                else
                {
                    receivedAudio[i / 4][0] = right;
                    receivedAudio[i / 4][1] = left;
                }

                logger.finest("Read from audio : " + audioInByte[i] + ", " + audioInByte[i + 1] + ", " + audioInByte[i + 2]
                        + ", " + audioInByte[i + 3] + ", " + left + ", " + right);
            }

            switch (requestedDemodulation[0])
            {
                case USB:
                case LSB:
                {
                    // check if the filter has not changed
                    if ((requestedFilterFrequency[0] != runningFilterFrequency) || requestedFilterWidth[0] != runningFilterWidth || requestedDemodulation[0] != runningDemodulation)
                    {
                        // for LSB, the spectrum is reversed on the display, so we must inverse the filter as well
                        if (requestedDemodulation[0] == LSB)
                        {
                            requestedFilterFrequency[0] = -requestedFilterFrequency[0] - requestedFilterWidth[0];
                        }

                        // we make the filter fft output twice as big, because we throw away half of it because of the image passband                
                        // add sampleRate/2 to the requested frequency, because 0 is in the middle of the xAxis
                        // divide by 2 because filter length has double length
                        calculatedFilterFrequency = ((int) sampleRate / 2 + requestedFilterFrequency[0]) / 2;
                        calculatedFilterWidth = requestedFilterWidth[0] / 2;
                        // calculatedFilterFrequency range is 0 to sampleRate/2
                        calculatedFilterFrequencyBin = 4 * calculatedFilterFrequency * fftSize / (int) sampleRate;
                        calculatedFilterWidthBin = 4 * calculatedFilterWidth * fftSize / (int) sampleRate;

                        BandPassFilter.CalculateFilter(filter, calculatedFilterFrequency, calculatedFilterWidth, sampleRate);
                        // !! the calculated filter freq takes into account that we throw away half of the spectrum !!
                        logger.fine("Filter : req freq : " + requestedFilterFrequency[0] + ", req width : " + requestedFilterWidth[0]
                                + ", cal freq : " + calculatedFilterFrequency + ", cal width : " + calculatedFilterWidth
                                + ", cal freq bin : " + calculatedFilterFrequencyBin + ", cal width bin : " + calculatedFilterWidthBin);

                        for (int i = 0; i < fftSize * 2; i++)
                        {
                            // verified, imag = 0, pointwise multiply is ok and keeps same values if filter[i] is 0
                            filterFFTIn[i] = new Complex(filter[i], 0);
                        }
                        for (int i = fftSize * 2; i < 4 * fftSize; i++) // changed here *2
                        {
                            filterFFTIn[i] = new Complex(0, 0);
                        }
                        tempFilterFFTOut = fft.FFT.fft(filterFFTIn);

                        for (int i = 0; i < fftSize * 2; i++)
                        {
                            filterFFTOut[i] = tempFilterFFTOut[i];
                        }

                        runningFilterFrequency = requestedFilterFrequency[0];
                        runningFilterWidth = requestedFilterWidth[0];
                        runningDemodulation = requestedDemodulation[0];
                    }

                    // reverse the spectrum in case of LSB
                    // we can do this by taking (I,-Q), or reversing I and Q
                    // see Quad Signals, by Richard Lyons
                    for (int i = 0; i < fftSize; i++)
                    {
                        switch (requestedDemodulation[0])
                        {
                            case USB:
                            {
                                audioFFTIn[i] = new Complex(receivedAudio[i][0], receivedAudio[i][1]);
                            }
                            break;

                            case LSB:
                            {
                                audioFFTIn[i] = new Complex(receivedAudio[i][0], -receivedAudio[i][1]);
                            }
                            break;
                        }
                    }

                    for (int i = fftSize; i < 2 * fftSize; i++)
                    {
                        audioFFTIn[i] = new Complex(0, 0);
                    }

                    // compute FFT of each sequence
                    audioFFTOut = fft.FFT.fft(audioFFTIn);

                    // swap the lower spectrum to high, and higher spectrun to low
                    // see fig 4 in QEX article
                    // N is the samplerate, split in 2 and move as follows :
                    // bin N/2 -- bin N-1 | center freq (no bin) | bin 0 -- bin N/2-1
                    // indexes are now :
                    // 0 -- N/2-1 | N/2 -- N-1

                    // first USB
                    for (int i = 0; i < fftSize; i++)
                    {
                        swappedAudioFFTOut[i + fftSize] = audioFFTOut[i];
                    }

                    // then LSB
                    for (int i = fftSize; i < fftSize * 2; i++)
                    {
                        swappedAudioFFTOut[i - fftSize] = audioFFTOut[i];
                    }

                    // each FFTOut is first padded with zeroes, then the FFTout, then a point wise multiply             
                    // fft convolve function makes 2 fft's, then multiply, then inverse fft
                    // multiply with audioFFTOut and not the reworked receivedBasebandFFT
                    // works fine : ConvolvedInTimeDomain = fft.FFT.convolve(audioFFTIn, filterFFTIn);

                    // multiply audioFFTOut with filterFFTOut
                    // point-wise multiply
                    Complex[] c = new Complex[fftSize * 2];
                    for (int i = 0; i < fftSize * 2; i++)
                    {
                        // c[i] = swappedAudioFFTOut[i].times(filterFFTOut[i]);
                        c[i] = filterFFTOut[i].times(swappedAudioFFTOut[i]);
                    }

                    // now we shift the filtered spectrum to zero
                    Complex[] d = new Complex[fftSize * 2];

                    for (int i = 0; i < fftSize * 2; i++)
                    {
                        d[i] = new Complex(0, 0);
                    }

                    for (int i = 0; i < calculatedFilterWidthBin; i++)
                    {
                        d[i] = c[calculatedFilterFrequencyBin + i];
                    }

                    for (int i = 0; i < fftSize * 2; i++)
                    {
                        switch (requestedDemodulation[0])
                        {
                            case USB:
                            {
                                receivedBasebandFFT[i] = (int) (swappedAudioFFTOut[i].abs()); // for the unfiltered baseband spectrum
                                // receivedBasebandFFT[iWork][i] = (int) swappedAudioFFTOut[i].abs(); // for the unfiltered baseband spectrum
                                // receivedBasebandFFT[iWork][i] = (int) c[i].abs(); // for the filtered baseband spectrum
                                // receivedBasebandFFT[iWork][i] = (int) d[i].abs(); // for the filtered baseband spectrum, moved into the audible spectrum
                                // receivedBasebandFFT[iWork][i] = (int) (1000 * filterFFTOut[i].abs()); // for the filter, multiply with 1000, otherwise int gives 0
                                // receivedBasebandFFT[iWork][i] = (int) (1000 * filter[i]); // for the positive values of the filter 
                            }
                            break;

                            case LSB:
                            {
                                receivedBasebandFFT[i] = (int) swappedAudioFFTOut[fftSize * 2 - i - 1].abs(); // for the unfiltered baseband spectrum
                                // receivedBasebandFFT[iWork][i] = (int) c[i].abs(); // for the filtered baseband spectrum
                                // receivedBasebandFFT[iWork][i] = (int) d[i].abs(); // for the filtered baseband spectrum, moved into the audible spectrum
                                // receivedBasebandFFT[iWork][i] = (int) (1000 * filterFFTOut[i].abs()); // for the filter, multiply with 1000, otherwise int gives 0
                                // receivedBasebandFFT[iWork][i] = (int) (1000 * filter[i]); // for the positive values of the filter 
                            }
                            break;
                        }
                    }

                    // queue the receivedBasebandFFT buffer
                    lbdReceivedBasebandFFT.add(receivedBasebandFFT);

                    ConvolvedInTimeDomain = fft.FFT.ifft(d);
                    logger.finest("audioFFTOut : " + audioFFTOut.length + ", filterFFTOut : " + filterFFTOut.length + ", ConvolvedInTimeDomain : " + ConvolvedInTimeDomain.length);

                    // first half of the convolveOutput + Overlap            
                    for (int i = 0; i < fftSize; i++)
                    {
                        double dre = ConvolvedInTimeDomain[i].re() + overlap[i].re();
                        double dim = ConvolvedInTimeDomain[i].im() + overlap[i].im();

                        int ire = (int) (dre);
                        int iim = (int) (dim);
                        // receivedAudioToSoundcard[i][0] = iabs;
                        receivedAudioToSoundcard[i] = iim;
                    }
                    // second half goes to the overlap
                    for (int i = 0; i < fftSize; i++)
                    {
                        overlap[i] = new Complex(ConvolvedInTimeDomain[i + fftSize].re(), ConvolvedInTimeDomain[i + fftSize].im());
                    }
                }
                break;

                case IPlusQ:
                case IMinusQ:
                {
                    Complex[] shortAudioFFTIn = new Complex[fftSize];
                    Complex[] shortSwappedAudioFFTOut = new Complex[fftSize];
                    Complex[] shortAudioIFFT = null;

                    // fft I and Q, if not the spectrum display will contain images
                    for (int i = 0; i < fftSize; i++)
                    {
                        shortAudioFFTIn[i] = new Complex(receivedAudio[i][0], receivedAudio[i][1]); // Q is to the FFT
                    }

                    // compute FFT of each sequence
                    audioFFTOut = fft.FFT.fft(shortAudioFFTIn);

                    // swap the lower spectrum to high, and higher spectrun to low
                    // see fig 4 in QEX article
                    // N is the samplerate, split in 2 and move as follows :
                    // bin N/2 -- bin N-1 | center freq (no bin) | bin 0 -- bin N/2-1
                    // indexes are now :
                    // 0 -- N/2-1 | N/2 -- N-1

                    // first USB
                    for (int i = 0; i < fftSize / 2; i++)
                    {
                        shortSwappedAudioFFTOut[i + fftSize / 2] = audioFFTOut[i];
                    }

                    // then LSB
                    for (int i = fftSize / 2; i < fftSize; i++)
                    {
                        shortSwappedAudioFFTOut[i - fftSize / 2] = audioFFTOut[i];
                    }

                    for (int i = 0; i < fftSize; i++)
                    {
                        receivedBasebandFFT[2 * i] = (int) (shortSwappedAudioFFTOut[i].abs());
                        receivedBasebandFFT[2 * i + 1] = (int) (shortSwappedAudioFFTOut[i].abs());
                    }
                    // queue the receivedBasebandFFT buffer
                    lbdReceivedBasebandFFT.add(receivedBasebandFFT);



                    // new hilbert filter
                    for (int i = 0; i < fftSize; i++)
                    {
                        shortAudioFFTIn[i] = new Complex(receivedAudio[i][1], 0); // Q is to the FFT
                    }

                    // compute FFT of each sequence
                    audioFFTOut = fft.FFT.fft(shortAudioFFTIn);
                    // ComplexArray X = FFTMixedRadix.fftReal(x, N);
                    double[] H = new double[fftSize];

                    int NOver2 = (int) Math.floor(fftSize / 2 + 0.5);
                    int w;

                    H[0] = 1.0;
                    H[NOver2] = 1.0;

                    for (w = 1; w <= NOver2 - 1; w++)
                    {
                        H[w] = 2.0;
                    }

                    for (w = NOver2 + 1; w <= fftSize - 1; w++)
                    {
                        H[w] = 0.0;
                    }

                    for (w = 0; w < fftSize; w++)
                    {
                        audioFFTOut[w] = new Complex(audioFFTOut[w].re() * H[w], audioFFTOut[w].im() * H[w]);
                        // X.real[w] *= H[w];
                        // X.imag[w] *= H[w];
                    }

                    //return FFTMixedRadix.ifft(X);
                    shortAudioIFFT = fft.FFT.ifft(audioFFTOut);

                    for (int i = 0; i < fftSize; i++)
                    {
                        if (requestedDemodulation[0] == IMinusQ)
                        {
                            receivedAudioToSoundcard[i] = receivedAudio[i][0] - (int) shortAudioIFFT[i].im();
                        }
                        else
                        // IPlusQ:
                        {
                            receivedAudioToSoundcard[i] = receivedAudio[i][0] + (int) shortAudioIFFT[i].im();
                        }
                    }

                    // end of new hilbert filter


                    /*
                     // hilbert transformation with the marytts
                     // prepare the Q buffer

                     double[] q = new double[fftSize];
                     ComplexArray t = new ComplexArray(fftSize);
                     for (int i = 0; i < fftSize; i++)
                     {
                     q[i] = receivedAudio[i][1];
                     }


                     Hilbert hilbert = new Hilbert();
                     t = hilbert.transform(q, fftSize);

                     for (int i = 0; i < fftSize; i++)
                     {
                     if (requestedDemodulation[0] == IMinusQ)
                     {
                     receivedAudioToSoundcard[i] = receivedAudio[i][0] - (int) t.get(i).imag;
                     } else
                     // IPlusQ:
                     {
                     receivedAudioToSoundcard[i] = receivedAudio[i][0] + (int) t.get(i).imag;
                     }
                     }
                     */

                    /*
                     // mines hilbert filter

                     float[] q = new float[fftSize];
                     float[] t = new float[fftSize];
                     for (int i = 0; i < fftSize; i++)
                     {
                     q[i] = receivedAudio[i][1];
                     }

                     HilbertTransformFilter hilbert = new HilbertTransformFilter();
                     hilbert.apply(fftSize, q, t);

                     for (int i = 0; i < fftSize; i++)
                     {
                     if (requestedDemodulation[0] == IMinusQ)
                     {
                     receivedAudioToSoundcard[i] = receivedAudio[i][0] - (int) t[i];
                     } else
                     // IPlusQ:
                     {
                     receivedAudioToSoundcard[i] = receivedAudio[i][0] + (int) t[i];
                     }
                     }
                     */
                }
                break;
            }

            // queue the receivedAudioToSoundcard buffer
            lbdReceivedAudioToSoundcard.add(receivedAudioToSoundcard);

            logger.fine("New received audio buffer processed, size : " + audioInReadInBytes);
        }

        targetDataLine.close();
        targetDataLine.stop();
        lbdReceivedBasebandFFT.clear();
        lbdReceivedAudioToSoundcard.clear();

        logger.info("AudioIn thread exit");
    }
}
