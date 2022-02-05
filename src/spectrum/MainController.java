// Erik Icket, ON4PB - 2022
package spectrum;

import audio.AudioIn;
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

    PropertiesWrapper propWrapper = new PropertiesWrapper();

    static public Complex[] complexOut = new Complex[FFTSIZE];
    static public boolean newBuffer = false;

    private XYChart.Series<Number, Number> series = new XYChart.Series<Number, Number>();
    private Timeline timeline;
    private AudioInThread audioInThread;

    @FXML
    private NumberAxis xAxis;
    private double lastXEntered;
    private double deltaX;

    @FXML
    void enter(MouseEvent event)
    {
        logger.info("Mouse entered, x : " + event.getX() + ", y: " + event.getY());
        lastXEntered = event.getX();

    }

    @FXML
    void release(MouseEvent event)
    {

        deltaX = event.getX() - lastXEntered;
        double scaled = deltaX / 900; // between 0 and 1 of the xaxis displacement
        double xAxisBoundaries = xAxis.getUpperBound() - xAxis.getLowerBound();
        double deltaAxis = scaled * xAxisBoundaries;

        logger.info("Mouse released, x : " + event.getX() + ", y: " + event.getY() + ", delta in px : " + deltaX + ", delta in scale : " + deltaAxis);
        xAxis.setLowerBound(xAxis.getLowerBound() - deltaAxis);
        xAxis.setUpperBound(xAxis.getUpperBound() - deltaAxis);

    }

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
        logger.info("Monitor button clicked");

        if ((audioInThread == null) || !audioInThread.isAlive())
        {
            propWrapper.setProperty("ReceivedAudioIn", audioInBox.getValue());
            logger.info("Audio in : " + audioInBox.getValue());

            audioInThread = new AudioInThread(audioInBox.getValue());
            audioInThread.start();

            xAxis.setLowerBound(0);
            xAxis.setUpperBound(6000);
            xAxis.setTickUnit((xAxis.getUpperBound() - xAxis.getLowerBound()) / 20);

            monitorButton.setStyle("-fx-background-color: Salmon");
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

            monitorButton.setStyle("-fx-background-color: NavajoWhite");
        }
    }

    @FXML
    void xAxisOnScroll(ScrollEvent event)
    {
        double axisScale = xAxis.getUpperBound() - xAxis.getLowerBound();
        double delta = axisScale / 4;

        if (event.getDeltaY() > 0)
        {

            xAxis.setUpperBound(xAxis.getUpperBound() + delta);
            xAxis.setLowerBound(xAxis.getLowerBound() - delta);

            /*
            xAxis.setUpperBound(2 * xAxis.getUpperBound());
            xAxis.setLowerBound(xAxis.getLowerBound() / 2);
             */
            //  xAxis.setTickUnit(xAxis.getUpperBound() / 20);
        }
        else
        {
            xAxis.setUpperBound(xAxis.getUpperBound() - delta);
            xAxis.setLowerBound(xAxis.getLowerBound() + delta);

            /*
            xAxis.setUpperBound(xAxis.getUpperBound() / 2);
            xAxis.setLowerBound(2 * xAxis.getLowerBound());
             */
            // xAxis.setTickUnit(xAxis.getUpperBound() / 20);
        }

        xAxis.setTickUnit((xAxis.getUpperBound() - xAxis.getLowerBound()) / 20);
        xAxis.requestLayout();

        logger.info("xAxis lower bound : " + xAxis.getLowerBound() + ", upper : " + xAxis.getUpperBound() + ", tick unit : " + (xAxis.getUpperBound() - xAxis.getLowerBound()) / 20);
    }

    @FXML
    void yAxisOnScroll(ScrollEvent event)
    {
        if (event.getDeltaY() > 0)
        {
            if (yAxis.getUpperBound() < 32000000)
            {
                yAxis.setUpperBound((2 * yAxis.getUpperBound()));
                yAxis.setTickUnit(yAxis.getUpperBound() / 20);
            }
        }
        else
        {
            yAxis.setUpperBound((yAxis.getUpperBound() / 2));
            yAxis.setTickUnit(yAxis.getUpperBound() / 20);
        }
        yAxis.requestLayout();

        logger.fine("yAxis upper bound : " + yAxis.getUpperBound());
    }

    @FXML
    void initialize()
    {
        monitorButton.setStyle("-fx-background-color: NavajoWhite");

        for (int i = 0; i < complexOut.length; i++)
        {
            complexOut[i] = new Complex(0, 0);
        }

        AudioIn.ListAudioIn(audioInBox);

        lineChart.setCreateSymbols(true);
        lineChart.getData().add(series);

        timeline = new Timeline();

        timeline.getKeyFrames().add(new KeyFrame(Duration.millis(50), new EventHandler<ActionEvent>()
        {
            @Override
            public void handle(ActionEvent actionEvent)
            {
                logger.fine("Animation event received");

                if (newBuffer)
                {
                    lineChart.setAnimated(false);
                    series.getData().clear();

                    for (int i = 0; i < complexOut.length / 2; i++)
                    {
                        // double val = 10 * Math.log10(complexOut[i].abs());
                        double val = complexOut[i].abs();
                        series.getData().add(new XYChart.Data<Number, Number>(i * (float) SAMPLE_RATE / FFTSIZE, val));
                    }
                    lineChart.setAnimated(true);
                    newBuffer = false;
                }
            }
        }
        ));
        timeline.setCycleCount(Animation.INDEFINITE);

        timeline.play();
    }
}
