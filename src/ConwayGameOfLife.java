import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;

import javax.sound.midi.InvalidMidiDataException;
import javax.sound.midi.MidiDevice;
import javax.sound.midi.MidiDevice.Info;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.MidiUnavailableException;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;

public class ConwayGameOfLife {

	public static void main(String[] args) {
		new ConwayGameOfLife();
	}
	MidiDevice device = null;
	Receiver receiver = null;
	Random random = new Random();
	int w = 16;
	int h = 8;
	int cellSize = 20;
	float prob = 0.5f;
	Boolean[][] boolArray = createBoolArray(prob);
	
	Boolean[][] createBoolArray(float prob) {
		return IntStream.range(0, w).mapToObj(x->IntStream.range(0, h)
				.mapToObj(y->Math.random()<=prob).toArray(Boolean[]::new)).toArray(Boolean[][]::new);
	}
	
	AtomicReference<Duration> pulseSpacing = new AtomicReference<>(Duration.ofMillis(300));
	Deque<Instant> tapTempoQueue = new ArrayDeque<>();
	
	Canvas canvas;

	

	List<Boolean> getNeighbors(int x, int y) {
		List<Boolean> list = new ArrayList<>();
		for (int a : new int[] {x-1,x,x+1}) {
			for (int b : new int[] {y-1,y,y+1}) {
				if (a >= 0 && b >= 0 && a < w && b < h) {
					if (!(a == x && b == y)) {
						list.add(boolArray[a][b]);
					}
				}
			}
		}
		return list;
	}
	
	Boolean[][] nextIteration(Boolean[][] boolArray) {
		return IntStream.range(0, w).mapToObj(x->IntStream.range(0, h)
				.mapToObj(y->{
					List<Boolean> neighbors = getNeighbors(x,y);
					long tCount = neighbors.stream().filter(a->a).count();					
					boolean status = boolArray[x][y];
					if (status) {
						if (tCount < 2) {
							return false;
						} else if (tCount == 2 || tCount == 3) {
							return true;
						}else {
							return false;
						}
					} else {
						if (tCount == 3) {
							return true;
						} else {
							return false;
						}
					}					
				}).toArray(Boolean[]::new)).toArray(Boolean[][]::new);
	}
	
		
	void initializeMidi() {
		Info[] deviceInfos = MidiSystem.getMidiDeviceInfo();

		for (Info deviceInfo : deviceInfos) {
			if (deviceInfo.getName().equals("ot-1")) {
				try {
					device = MidiSystem.getMidiDevice(deviceInfo);
					device.open();
					receiver = device.getReceiver();
				} catch (MidiUnavailableException e) {
					
					e.printStackTrace();
				}
				break;				 
			}
		}
	}
	
	void createSCL() {
		try {
			double[] pitches =
					DoubleStream.of(1f/11f,7f/44f).flatMap(f->DoubleStream.iterate(12f, i->i+1).limit(16).map(i->i*f))
					.toArray();
			PrintWriter pw = new PrintWriter("st25r1.scl");
			pw.println("custom scale");
			pw.println(pitches.length);
			for (double pitch : pitches) {				
				pw.printf(" %.4f\n", pitch*1200);
			}
			pw.flush();
			pw.close();
		} catch (Exception ex) {
			
		}
		
		
		
	}
	ConwayGameOfLife() {
		createSCL();
		
		initializeMidi();
		
		JFrame frame = new JFrame();
		
		canvas = new Canvas() {
			@Override
			public Dimension getPreferredSize() {
				return new Dimension(w*cellSize,h*cellSize);
			}
			
			@Override
			public void paint(Graphics g) {
				Graphics2D g2d = (Graphics2D) g;
				for (int x = 0; x < w; x++) {
					for (int y = 0; y < h; y++) {
						boolean b = boolArray[x][y];
						g2d.setPaint(b?Color.RED:Color.BLUE);
						g2d.fillRect(x*cellSize,y*cellSize,cellSize,cellSize);
					}
				}
			}
		};
		
		JPanel bottomPanel = new JPanel(new GridLayout(0,3));
		
		JButton tapTempoButton = new JButton("O");
		tapTempoButton.addActionListener(ae -> {
			//tapTempoQueue
			//pulseSpacing
			while (tapTempoQueue.size() >= 2) {
				tapTempoQueue.poll();
			}
			tapTempoQueue.addLast(Instant.now());
			if (tapTempoQueue.size() == 2) {
				Duration dur = Duration.between(tapTempoQueue.peekFirst(), tapTempoQueue.peekLast());
				if (Duration.ofSeconds(2).minus(dur).isNegative()) {
					dur = Duration.ofSeconds(2);
				}
				pulseSpacing.set(dur);
			}
		});
		JButton resetButton = new JButton("!!");
		resetButton.addActionListener(ae -> {
			turnOffMidiNotes(boolArray);
						
			boolArray = createBoolArray(prob);
			turnOnMidiNotes(boolArray);
			canvas.repaint();
		});
		JButton nextButton = new JButton("->");
		nextButton.addActionListener(ae ->{
			iterate();			
		});
		
		Thread t = new Thread(() -> {
			while(true) {				
				iterate();
				try {
					Thread.sleep(pulseSpacing.get());
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		});
		t.start();
		
		bottomPanel.add(tapTempoButton);
		bottomPanel.add(resetButton);
		bottomPanel.add(nextButton);
		frame.getContentPane().add(bottomPanel,BorderLayout.SOUTH);
		frame.getContentPane().add(canvas,BorderLayout.CENTER);
		frame.pack();
		frame.setLocationRelativeTo(null);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}
	
	
	int xyToMidiNote(int x, int y) {
		return 60+x + (y%2)*w;
	}
	
	int xyToMidiChannel(int x, int y) {
		return (int) Math.floor(y*0.5);
	}
	
	
	void turnOffMidiNotes(Boolean[][] bools) {
		
		Set<ShortMessage> msgs = new HashSet<>();
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				if (bools[x][y]) {
					try {
						msgs.add(new ShortMessage(ShortMessage.NOTE_OFF,xyToMidiChannel(x,y),xyToMidiNote(x,y),0));
					} catch (InvalidMidiDataException e) {
						e.printStackTrace();
					}
				}
			}
		}
		msgs.forEach(msg -> {
			receiver.send(msg, -1);
		});
		//System.err.println(msgs);
	}
	
	void turnOnMidiNotes(Boolean[][] bools) {
		Set<ShortMessage> msgs= new HashSet<>();
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				if (bools[x][y]) {
					try {
						msgs.add(new ShortMessage(ShortMessage.NOTE_ON,xyToMidiChannel(x,y),xyToMidiNote(x,y),120));
					} catch (InvalidMidiDataException e) {
						e.printStackTrace();
					}
					
				}
			}
		}
		msgs.forEach(msg -> {
			receiver.send(msg, -1);
		});
		
	}
	void iterate() {
		turnOffMidiNotes(boolArray);
		boolArray = nextIteration(boolArray);
		turnOnMidiNotes(boolArray);
		canvas.repaint();
	}
	
	
}
