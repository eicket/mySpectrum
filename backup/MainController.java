package spectrum;

import audio.Audio;
import audio.AudioInThread;
import static common.Constants.FFTSIZE;
import static common.Constants.SAMPLE_RATE;
import common.PropertiesWrapper;
import fft.Complex;
import java.util.logging.Logger;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.util.Duration;

public class MainController
{

    static final Logger logger = Logger.getLogger(MainController.class.getName());

    // load the properties
    PropertiesWrapper propWrapper = new PropertiesWrapper();

    Audio audio = new Audio();
    static public Complex[] complexOut = new Complex[FFTSIZE];

    private XYChart.Series<Number, Number> series = new XYChart.Series<Number, Number>();
    Timeline timeline;
    AudioInThread audioInThread;

    @FXML
    private NumberAxis xAxis;

    @FXML
    private NumberAxis yAxis;

    @FXML
    private LineChart<Number, Number> lineChart;

    @FXML
    private ChoiceBox<String> audioInBox;

    @FXML
    private Button monitorButton;

    @FXML
    void monitorButtonClicked(MouseEvent event)
    {
        logger.info("Monitor button");

        if ((audioInThread == null) || !audioInThread.isAlive())
        {

            propWrapper.setProperty("ReceivedAudioIn", audioInBox.getValue());
            logger.info("Audio in : " + audioInBox.getValue());

            audioInThread = new AudioInThread();
            audioInThread.start();
        }
        else
        {
            if (audioInThread.isAlive())
            {
                audioInThread.stopRequest = true;
            }

            try
            {
                audioInThread.join();
            }
            catch (InterruptedException ex)
            {
                logger.fine("Exception when closing audioIn thread");
            }
            logger.info("AudioIn thread stopped");
        }
    }

    @FXML
    void xAxisOnScroll(ScrollEvent event)
    {
        if (event.getDeltaY() > 0)
        {
            xAxis.setUpperBound(2 * xAxis.getUpperBound());
            xAxis.setTickUnit(xAxis.getUpperBound() / 20);
        }
        else
        {
            xAxis.setUpperBound(xAxis.getUpperBound() / 2);
            xAxis.setTickUnit(xAxis.getUpperBound() / 20);
        }
        xAxis.requestLayout();

        logger.info("xAxis upper bound : " + xAxis.getUpperBound());
    }

    @FXML
    void yAxisOnScroll(ScrollEvent event)
    {
        if (event.getDeltaY() > 0)
        {
            if (yAxis.getUpperBound() < 32000000)
            {
                yAxis.setUpperBound( (2 * yAxis.getUpperBound()));
                yAxis.setTickUnit(yAxis.getUpperBound() / 20);
            }
        }
        else
        {
            yAxis.setUpperBound( (yAxis.getUpperBound() / 2));
            yAxis.setTickUnit(yAxis.getUpperBound() / 20);
        }
        yAxis.requestLayout();

        logger.info("yAxis upper bound : " + yAxis.getUpperBound());
    }

    @FXML
    void initialize()
    {
        for (int i = 0; i < complexOut.length; i++)
        {
            complexOut[i] = new Complex(0, 0);
        }

        audio.ListAudioIn(audioInBox);

        //    timeDomainChart.setLegendVisible(false);
        lineChart.setCreateSymbols(true);
        lineChart.getData().add(series);

        timeline = new Timeline();

        // 1 fft takes 341 msec
        timeline.getKeyFrames().add(new KeyFrame(Duration.millis(500), new EventHandler<ActionEvent>()
        {
            @Override
            public void handle(ActionEvent actionEvent)
            {
                logger.fine("Animation event received");

                lineChart.setAnimated(false);
                series.getData().clear();

                for (int i = 0; i < complexOut.length / 2; i++)
                {

                    // double val = 10 * Math.log10(complexOut[i].abs());
                    double val = complexOut[i].abs();
                    series.getData().add(new XYChart.Data<Number, Number>(i * SAMPLE_RATE / FFTSIZE, val));
                }
                lineChart.setAnimated(true);
            }
        }
        ));
        timeline.setCycleCount(Animation.INDEFINITE);

        timeline.play();
    }
}
