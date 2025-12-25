import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

public class KiteMOS {
	
	
	public static void main(String[] args) throws IOException, InterruptedException {
		
		JFrame frame = new JFrame();
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		
		JLabel generatorLabel = new JLabel("Generator");
		JLabel periodLabel = new JLabel("Period");
		JLabel startLabel = new JLabel("Start Note (relative to C, 261.63hz)");
		JSpinner generatorSpinner = new JSpinner(new SpinnerNumberModel(15,1,20,1));
		JSpinner periodSpinner = new JSpinner(new SpinnerNumberModel(41,2,100,1));
		JSpinner startSpinner = new JSpinner(new SpinnerNumberModel(20,0,100,1));
		JComboBox<NamedScale> mosComboBox = new JComboBox<>();
		JComboBox<TuningPatterns> tuningPatternComboBox = new JComboBox<>(TuningPatterns.values());
		FretboardCanvas canvas = new FretboardCanvas();
		tuningPatternComboBox.addActionListener(ae -> {
			canvas.setTuningPattern(((TuningPatterns) tuningPatternComboBox.getSelectedItem()).getPattern());
		});
		Runnable mosComboBoxFn = () -> {
			List<NamedScale> namedScales = 
					genMOS((int)startSpinner.getValue(),
							(int)periodSpinner.getValue(),
							(int)generatorSpinner.getValue(),
							(int)periodSpinner.getValue());
			
			DefaultComboBoxModel<NamedScale> model = new DefaultComboBoxModel<>();
			int index = mosComboBox.getSelectedIndex();
			model.addAll(namedScales);
			mosComboBox.setModel(model);			
			try {
				mosComboBox.setSelectedIndex(index);
			} catch (Exception ex) {
				mosComboBox.setSelectedIndex(0);
			}
		};
		
		Arrays.asList(generatorSpinner,periodSpinner,startSpinner).forEach(spinner -> spinner.addChangeListener(cl -> {
			mosComboBoxFn.run();
		}));
		
		mosComboBoxFn.run();
		mosComboBox.addActionListener(ae -> {
			canvas.repaint();
		});
		canvas.setPaintFunction(absEdoStepFromLowC -> {
			try {
				NamedScale scale = (NamedScale) mosComboBox.getSelectedItem();
				//int[] genSteps = new int[scale.steps.length];
				Set<Integer> degrees = Arrays.asList(scale.steps).stream().collect(Collectors.toSet());
				AtomicInteger a = new AtomicInteger((int) startSpinner.getValue());
				List<Integer> genSteps = 
						IntStream.range(0, scale.steps.length)
						.map(i->a.getAndUpdate(b->(b+(int) generatorSpinner.getValue())%((int) periodSpinner.getValue()))).boxed().toList();
				if (genSteps.indexOf(absEdoStepFromLowC%scale.period) != -1) {
					return Color.getHSBColor(((float) genSteps.indexOf(absEdoStepFromLowC%scale.period))/scale.steps.length,
							1f,1f);
				}
				//if (degrees.contains(absEdoStepFromLowC%scale.period)) {
					//return Color.black;
				//}
				return Color.white;
			} catch (Exception ex) {
				ex.printStackTrace();
				return Color.white;
			}
		});
		
		
		JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
		Arrays.asList(generatorLabel,generatorSpinner,periodLabel,periodSpinner,startLabel,startSpinner,mosComboBox,tuningPatternComboBox).forEach(comp -> {
			bottomPanel.add(comp);
		});
		
		frame.getContentPane().add(canvas,BorderLayout.CENTER);
		frame.getContentPane().add(bottomPanel,BorderLayout.SOUTH);
		canvas.setPreferredSize(new Dimension(Toolkit.getDefaultToolkit().getScreenSize().width,240));
		frame.pack();
		frame.setVisible(true);
		genMOS(20,41,15,41);
	}
	
	
	static List<NamedScale> genMOS(int start, int period, int generator, int noteLimit) {
		List<NamedScale> toReturn = new ArrayList<>();
		AtomicInteger a = new AtomicInteger(start%period);
		Set<Integer> scale = new TreeSet<>();
		Function<Integer[],Map<Integer,Integer>> computeIntervals = array -> {
			Map<Integer,Integer> intervals = new TreeMap<>();
			for (int i = 0; i < array.length; i++) {
				int interval = ((array[(i+1)%array.length]-array[i])+period)%period;
				intervals.compute(interval,(k,v)->v==null?1:v+1);
			}
			return intervals;
		};
		
		Predicate<Integer[]> hasTwoIntervalSizes = array -> {
			Map<Integer,Integer> intervals = computeIntervals.apply(array);		
			return intervals.size() == 2;
		};
		
	
		Predicate<Integer[]> allScaleIntervalsHaveTwoSizes = array -> {
			for (int intervalSize = 1; intervalSize < array.length-1; intervalSize++) {
				Set<Integer> intervalSizes = new HashSet<>();
				for (int i = 0; i < array.length; i++) {
					int note0 = array[i];
					int note1 = array[(i+intervalSize)%array.length];
					int interval = ((note1-note0)+period)%period;
					intervalSizes.add(interval);
				}
				if (intervalSizes.size()>2) {
					return false;
				}
			
			}
			return true;			
		};
		
		for (int i = 0; i < noteLimit; i++) {
			scale.add(a.getAndUpdate(b->(b+generator)%period));
			if (hasTwoIntervalSizes.and(allScaleIntervalsHaveTwoSizes).test(scale.toArray(Integer[]::new))) {
				Map<Integer,Integer> intervals = computeIntervals.apply(scale.toArray(Integer[]::new));
				Integer[] intervalCounts = intervals.values().toArray(Integer[]::new);
				Integer[] intervalA = intervals.keySet().toArray(Integer[]::new);
				NamedScale namedScale = new NamedScale(scale.toArray(Integer[]::new),period,
						String.format("%dL%ss (%s:%s)",intervalCounts[1],intervalCounts[0],intervalA[1],intervalA[0]));
				toReturn.add(namedScale);
				//System.out.println(namedScale.name);
				//System.out.println(intervals);
			}
		}
		return toReturn;
	}
	
}

class NamedScale {
	public final Integer period;
	public final Integer[] steps;
	public final String name;
	public NamedScale(Integer[] steps, Integer period, String name) {
		this.steps = steps;
		this.period = period;
		this.name = name;
	}
	
	public String toString() {
		return name;
	}

}

class TuningPattern {
	public final int[] steps;
	public final String name;
	
	public TuningPattern(int[] steps, String name) {
		this.steps = steps;
		this.name = name;
	}
	
	public Iterator<Integer> getIterator() {
		AtomicInteger i = new AtomicInteger(0);
		return IntStream.iterate(0, a->a+1).map(a->i.getAndUpdate(b->b+steps[a%steps.length])).iterator();
	}
	public String toString() {
		return name;
	}
}

enum TuningPatterns {
	DOWNMAJOR (new TuningPattern(new int[]{13},"Downmajor (+13)")),
	UPMINOR (new TuningPattern(new int[]{11},"Upminor (+11)")),
	PLATTER (new TuningPattern(new int[]{13,11},"Platter (+13,+11)"));
	
	TuningPattern pattern;
	public TuningPattern getPattern() {
		return pattern;
	}
	TuningPatterns(TuningPattern pattern) {
		this.pattern = pattern;
	}
}

class FretboardCanvas extends JPanel {

	
	public Function<Integer,Color> paintFunction = fret -> Color.WHITE;
	private TuningPattern tuningPattern = TuningPatterns.DOWNMAJOR.getPattern(); 
	public Function<Integer,Integer> stringStartNoteAboveC = s -> {
		Iterator<Integer> iter = tuningPattern.getIterator();
		return IntStream.range(0, 10).map(a->iter.next()).toArray()[s];		
	};

	
	public FretboardCanvas() {

	}
		
	public void setTuningPattern(TuningPattern pattern) {
		this.tuningPattern = pattern;
		repaint();
	}

	double normalizedFretPosition(double fret) {
		return 1 - (1 / Math.pow(2,fret/20.5));
	}
	
	public void setPaintFunction(Function<Integer,Color> f) {
		this.paintFunction = f;
	}

	@Override
	public void paint(Graphics g_) {
		Graphics2D g = (Graphics2D) g_;
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
		g.setPaint(Color.WHITE);
		g.fill(this.getBounds());
		g.setPaint(Color.BLACK);
		int stringHeight = 30;
		//Map<FretPosition,Rectangle> fretPositions;
		double xMax = normalizedFretPosition(42);
		Set<Double> fretNumbers = new TreeSet<>();
		IntStream.range(0, 42).mapToDouble(i->i).forEach(fretNumbers::add);
		Arrays.asList(1.5,3.5,4.5).forEach(fretNumbers::add);
		
		Double[] fretNumberArray = fretNumbers.toArray(new Double[] {});
		int bridgePosition= 20;
		Function<Double,Integer> fretPositionCalculator = fret -> {
			
			if (fret == 0) {
				return bridgePosition;
			}
			
			double d = 1 - (1 / (Math.pow(2, fret/20.5)));
			double d41 = 1 - (1 / (Math.pow(2, 41/20.5)));
			
			return (int) (d/d41*(getWidth()-bridgePosition)+bridgePosition);
			
		};
		
		for (int i = 1 ; i < fretNumberArray.length; i++) {
			double fretNumber = fretNumberArray[i];
			double prevFretNumber = fretNumberArray[i-1];
			int fretPosition = fretPositionCalculator.apply(fretNumber);
			int prevFretPosition = fretPositionCalculator.apply(prevFretNumber);
			double[] dotPositions = new double[] {};
			
			if (fretNumber%12 == 0) {
				dotPositions = new double[] {0.25,0.5,0.75};
			} else if (fretNumber%12 == 4) {
				dotPositions = new double[] {0.5};
			} else if (fretNumber%12 == 8) {
				dotPositions = new double[] {0.33,0.66};
			}
			
			for (double dotPosition : dotPositions) {
				double cx = 0.5*(fretPosition+prevFretPosition);
				int r = 2;
				g.fill(new Ellipse2D.Double(cx-r,stringHeight*dotPosition, r*2,r*2));
			}
			
			g.drawLine(fretPosition, stringHeight,fretPosition,stringHeight*8);
			if (fretNumber == 0) {
				g.drawLine(fretPosition-2, stringHeight, fretPosition-2, stringHeight*8);
			}
			for (int stringNumber = 0; stringNumber < 7; stringNumber++) {
				int absStepsAboveC = (int) (stringStartNoteAboveC.apply(stringNumber) + fretNumber*2);				
				
				g.setPaint(paintFunction.apply(absStepsAboveC));
				g.fill(new Rectangle2D.Double(prevFretPosition,stringHeight*(7-stringNumber),fretPosition-prevFretPosition,stringHeight));
				
				g.setPaint(Color.black);
				g.draw(new Rectangle2D.Double(prevFretPosition,stringHeight*(7-stringNumber),fretPosition-prevFretPosition,stringHeight));
				//g.drawString(absStepsAboveC+"",fretPosition+1,stringHeight*(8-stringNumber));
			}
		}
		
		
		for (int stringNumber = 0; stringNumber < 8; stringNumber++) {
			int y = (int) ((8-stringNumber)*stringHeight);
			g.setPaint(Color.BLACK);
			g.drawLine(bridgePosition, y, getWidth(), y);
			if (stringNumber<7) {
				int absEdoStepsAbvLowC = stringStartNoteAboveC.apply(stringNumber);
				g.setPaint(paintFunction.apply(absEdoStepsAbvLowC));
				//g.setPaint(Color.blue);
				g.fillRect(0, y-stringHeight, bridgePosition-2, stringHeight);
			}
			
			//g.drawString(13*stringNumber+"", 0, y);
		}
		
	}
	

}

