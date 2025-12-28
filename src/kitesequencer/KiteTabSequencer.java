package kitesequencer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog.ModalityType;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.MouseInfo;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.sound.midi.Instrument;
import javax.sound.midi.MidiChannel;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Soundbank;
import javax.sound.midi.Synthesizer;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.InputMap;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileFilter;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import kitesequencer.config.CanvasConfig;
import kitesequencer.config.CanvasesConfig;
import kitesequencer.config.DrumCanvasConfig;
import kitesequencer.config.PercRowToken;
import kitesequencer.config.StringCanvasConfig;
import kitesequencer.events.ControlEvent;
import kitesequencer.events.ControlEventType;
import kitesequencer.events.NilControlEvent;
import kitesequencer.events.StickyNote;
import kitesequencer.events.TempoEvent;
import kitesequencer.events.TimeSignatureDenominator;
import kitesequencer.events.TimeSignatureEvent;


public class KiteTabSequencer {
	
	public static void main(String[] args) {
		KiteTabSequencer.getInstance(); 
	}
	private static KiteTabSequencer instance;
	
	private Font font = new Font("Monospaced",Font.BOLD,11);
	private FontMetrics fontMetrics = new Canvas().getFontMetrics(font);
	int infoPanelHeight = fontMetrics.getMaxAscent()+10;
	int cellWidth = fontMetrics.stringWidth("88")+3;
	int rowHeight = fontMetrics.getMaxAscent()+2;
	final int scrollTimeMargin = 4;	
	final double middleC = 220.0 * Math.pow(2d, 3.0/12.0);	
	
	FileFilter fileFilter = new FileNameExtensionFilter(".tab files (.tab)","tab");
	final File defaultPath = new File("scores");			
	final AtomicReference<File> activeFile = new AtomicReference<>(null);	
	final AtomicBoolean fileHasBeenModified = new AtomicBoolean(false);
	
	final AtomicBoolean isPlaying = new AtomicBoolean(false);
	final AtomicInteger playT = new AtomicInteger(0);
	final AtomicInteger viewT0 = new AtomicInteger(-scrollTimeMargin);
	final AtomicInteger cursorT = new AtomicInteger(0);
	final AtomicInteger stopT0 = new AtomicInteger(0);
	final AtomicInteger repeatT = new AtomicInteger(-1);
	final AtomicInteger tempo = new AtomicInteger(120);
	final AtomicBoolean playbackDaemonIsStarted = new AtomicBoolean(false);
		 
	final TreeMap<Integer,Integer> cachedMeasurePositions = new TreeMap<>();
	final HashSet<Integer> cachedBeatMarkerPositions = new HashSet<>();
	
	final ScheduledExecutorService playbackDaemon = Executors.newSingleThreadScheduledExecutor();
	final ScheduledExecutorService midiDaemon = Executors.newSingleThreadScheduledExecutor();	
		
	final JFrame frame = new JFrame("Kite Tab Sequencer 2000");
	
	LeftClickablePanelButton playButton, stopButton;
	final PlayStatusPanel playStatusPanel = new PlayStatusPanel();
	
	final EventCanvas eventCanvas = new EventCanvas();
	
	File defaultSoundfontFile = null;
	final NavigationCanvas navigationBar = new NavigationCanvas();
	final List<KiteTabCanvas<?>> allCanvases = new ArrayList<>(Arrays.asList(eventCanvas));
	final List<SoundfontPlayer> allSoundPlayers = new ArrayList<>();
	final AtomicReference<KiteTabCanvas<?>> selectedCanvas = new AtomicReference<>(eventCanvas);
	
	void playbackDaemonFunction() {
		//DO NOT CALL THIS ON MASTER THREAD
		while (true) {
			if (!isPlaying.get()) {
				continue;
			}
			else {
				int t = playT.get();
				
				try {
					
					midiDaemon.execute(() -> {
						allCanvases.forEach(a->a.handleEvents(t));	
					});
					
					SwingUtilities.invokeAndWait(() -> {						
						frame.repaint();							
					});
						
				} catch (Exception e) {					
					e.printStackTrace();
				}
				
				playT.getAndUpdate(i->i+1==repeatT.get()?stopT0.get():i+1);
				long bpm = tempo.get();				
				Duration sixteenth = Duration.ofMinutes(1).dividedBy(bpm).dividedBy(4);  
				playbackDaemon.schedule(()->playbackDaemonFunction(),
						sixteenth.toMillis(),TimeUnit.MILLISECONDS);
				
				return;
			}
		}
	}
	
	void repaintCanvases() {
		allCanvases.forEach(a->a.repaint());		
	}
	
	void addStaccato() {
		
	}
	
	void addAccent() {
		
	}
	
	void configureCanvasInstrument() {
		if (selectedCanvas.get() instanceof TabCanvas) {
			((TabCanvas)selectedCanvas.get()).displayInstrumentDialog();
		}// else if (selectedCanvas.get() instanceof DrumTabCanvas) {
			//((DrumTabCanvas)selectedCanvas.get()).displayInstrumentDialog();
		//}
	}
	
	void saveActiveFile() {
		if (!defaultPath.exists()) {
			defaultPath.mkdir();
		}
		if (activeFile.get() != null) {
			if (fileHasBeenModified.get()) {
				try {
					saveXML(activeFile.get());
					fileHasBeenModified.set(false);
					updateWindowTitle();
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
		} else {
			JFileChooser chooser = new JFileChooser(defaultPath);
			chooser.setFileFilter(fileFilter);
			
			String date = LocalDateTime.now(ZoneId.of("Z")).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
			chooser.setSelectedFile(new File(date+".tab"));
			if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
				try {
					File f = chooser.getSelectedFile();
					if (!f.getAbsolutePath().endsWith(".tab")) {
						f = new File(f.getAbsoluteFile()+".tab");
					}
					saveXML(chooser.getSelectedFile());
					activeFile.set(chooser.getSelectedFile());
					fileHasBeenModified.set(false);
					updateWindowTitle();
				} catch (Exception ex) {
					ex.printStackTrace();					
				}
			}
			
		}
	}
	
	void openFileDialog() {
		File defaultPath = new File("scores");
		if (!defaultPath.exists()) {
			defaultPath.mkdir();
		}
		JFileChooser fileChooser = new JFileChooser(defaultPath);
		fileChooser.setFileFilter(fileFilter);
		if (fileChooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {			
			try {
				
				loadXML(fileChooser.getSelectedFile());
				activeFile.set(fileChooser.getSelectedFile());
				fileHasBeenModified.set(false);
				updateWindowTitle();
			} catch (Exception ex) {
				ex.printStackTrace();			
			}
		}
	}
	
	void updateWindowTitle() {
		StringBuilder sb = new StringBuilder();
		sb.append("KiteTabSequencer2000");
		if (activeFile.get() == null) {
			if (fileHasBeenModified.get()) {
				sb.append(" (unsaved)");
			} 
		} else {
			sb.append(" (");
			sb.append(activeFile.get().getName());
			if (fileHasBeenModified.get()) {
				sb.append(" * ");				
			}
			sb.append(")");
		}
		frame.setTitle(sb.toString());
		
	}
	
	void adjustRepeatT() {
		repeatT.updateAndGet(i->i==cursorT.get()?-1:cursorT.get());
		repaintCanvases();
	}
	
	void setPlayT() {
		playT.set(cursorT.get());
		repaintCanvases();
	}
	
	void togglePlayStatus() {
		if (isPlaying.get()) {
			stopPlayback();
		} else {
			startPlayback();
		}		
	}
	
	void returnToStopT0() {
		//TODO am i going to rewrite this
	}
	
	void startPlayback() {
		if (!playbackDaemonIsStarted.get()) {
			playbackDaemonIsStarted.set(true);
			playbackDaemon.schedule(()->playbackDaemonFunction(),0, TimeUnit.SECONDS);
		}
		isPlaying.set(true);
		playStatusPanel.setPlayStatus(PlayStatus.PLAY);
		playStatusPanel.repaint();
	}
		
	
	void stopPlayback() {		
		isPlaying.set(false);
		
		allSoundPlayers.forEach(soundfontPlayer -> {
			soundfontPlayer.silence();
		});

		repaintCanvases();
		playStatusPanel.setPlayStatus(PlayStatus.STOP);
		playStatusPanel.repaint();	
	}
	
	void setStopT0() {
		stopT0.set(cursorT.get());
		repaintCanvases();
	}
		
	public int getCursorT() {
		return cursorT.get();
	}
	
	public void setCursorT(int t) {
		cursorT.set(t);
	}
	
	public void incrementCursorT() {		
		cursorT.getAndIncrement();
		if (cursorT.get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			viewT0.getAndIncrement();
		}
		repaintCanvases();
	}
	
	public void decrementCursorT() {
		cursorT.set(Math.max(0, cursorT.get()-1));
		if (cursorT.get() < viewT0.get()+scrollTimeMargin) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
	
	public void updateMeasureLinePositions() {		
		AtomicReference<TimeSignatureEvent> timeSignature = new AtomicReference<>(new TimeSignatureEvent(4,TimeSignatureDenominator._4));
		AtomicInteger counter = new AtomicInteger(0);
		AtomicInteger measure = new AtomicInteger(1);
		Map<Integer,Integer> measures = new TreeMap<>();
		Set<Integer> markers = new HashSet<>();
		measures.put(0,measure.getAndIncrement());
		for (int t = 0; t < 1000*16; t++) {
			Optional<TimeSignatureEvent> tsO = 
					eventCanvas.data.getOrDefault(t,Collections.emptyMap()).values().stream()
					.filter(a->a.getType()==ControlEventType.TIME_SIGNATURE)
					.map(a->(TimeSignatureEvent) a)
					.findFirst();
			if (tsO.isPresent()) {				
				timeSignature.set(tsO.get());				
				counter.set(0);
				measures.put(t,measure.getAndIncrement());	
			}
			if (counter.get() == timeSignature.get().get16ths()) {
				counter.set(0);
				measures.put(t,measure.getAndIncrement());								
			}			
			if (counter.get() > 0 && counter.get()%(16/timeSignature.get().denominator.getValue()) == 0) {
				markers.add(t);
			}
			counter.incrementAndGet();
		}
		cachedMeasurePositions.clear();
		cachedBeatMarkerPositions.clear();
		cachedMeasurePositions.putAll(measures);
		cachedBeatMarkerPositions.addAll(markers);
	}
	
	public void backspace() {
		Optional<?> removed = selectedCanvas.get().removeSelectedValue();
		
		removed.filter(a->a instanceof TimeSignatureEvent).ifPresent(object ->{
			updateMeasureLinePositions();
			repaintCanvases();
		});
			
		if (!fileHasBeenModified.get()) {
			fileHasBeenModified.set(true);
			updateWindowTitle();
		}
		selectedCanvas.get().repaint();
	}
	
	public void handleABCInput(char c) {
		selectedCanvas.get().handleCharInput(c);
		
	}
	
	public void cursorToStart() {
		cursorT.set(0);
		while (cursorT.get() < viewT0.get()) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
	
	
	public void cursorToEnd() {
		//TODO finish this...will move the cursor to
		//the last input note
	}
	
	public void playTToPreviousMeasure() {
		NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(playT.get(), false);
		if (!headMap.isEmpty()) {
			Entry<Integer, Integer> last= headMap.lastEntry();			
			playT.set(last.getKey());			
		}
		while (playT.get() < viewT0.get()+scrollTimeMargin) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
	
	public void playTToNextMeasure() {
		NavigableMap<Integer, Integer> tailMap = cachedMeasurePositions.tailMap(playT.get(), false);
		if (!tailMap.isEmpty()) {
			Entry<Integer, Integer> next = tailMap.firstEntry();			
			playT.set(next.getKey());			
		}
		while (playT.get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			viewT0.getAndIncrement();
		}
		repaintCanvases();
	}
	
	public void incrementPlayT() {
		playT.incrementAndGet();
		while (playT.get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			viewT0.getAndIncrement();
		}
		repaintCanvases();
	}
	
	public void decrementPlayT() {
		playT.getAndUpdate(i->Math.max(0,i-1));
		while (playT.get() < viewT0.get()+scrollTimeMargin) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
	
	public void shiftArrowUp() {
		if (selectedCanvas.get() == null) {
			selectedCanvas.set(allCanvases.get(0));
			allCanvases.get(0).setSelectedRow(allCanvases.get(0).getRowCount()-1);
			selectedCanvas.get().repaint();
		} else {			
			int index = allCanvases.indexOf(selectedCanvas.get())-1;
			if (index == -1) {
				index+=allCanvases.size();
			}
			selectedCanvas.get().repaint();
			selectedCanvas.set(allCanvases.get(index));							
			selectedCanvas.get().setSelectedRow(selectedCanvas.get().getRowCount()-1);
			selectedCanvas.get().repaint();							
		}
		
	}
	
	public void shiftArrowDown() {
		if (selectedCanvas.get() == null) {
			selectedCanvas.set(allCanvases.get(allCanvases.size()-1));
			eventCanvas.setSelectedRow(0);
			eventCanvas.repaint();
		} else {			
			int index = allCanvases.indexOf(selectedCanvas.get())+1;
			if (index == allCanvases.size()) {
				index = 0;
			}
			selectedCanvas.get().repaint();
			selectedCanvas.set(allCanvases.get(index));				
			selectedCanvas.get().setSelectedRow(0);
			selectedCanvas.get().repaint();					
		}	
	}
	
	public void arrowUp() {
		if (selectedCanvas.get() == null) {
			selectedCanvas.set(allCanvases.get(0));
			allCanvases.get(0).setSelectedRow(allCanvases.get(0).getRowCount()-1);
			selectedCanvas.get().repaint();
		}
		else {			
			if (selectedCanvas.get().getSelectedRow() == 0) {
				shiftArrowUp();				
			} else {
				selectedCanvas.get().setSelectedRow(selectedCanvas.get().getSelectedRow()-1);
				selectedCanvas.get().repaint();
			}
		}
		
	}
	
	public void arrowDown() {
		if (selectedCanvas.get() == null) {
			selectedCanvas.set(allCanvases.get(allCanvases.size()-1));
			eventCanvas.setSelectedRow(0);
			eventCanvas.repaint();		
		} else {
			if (selectedCanvas.get().getSelectedRow() == selectedCanvas.get().getRowCount()-1) {
				shiftArrowDown();				
			} else {
				selectedCanvas.get().setSelectedRow(selectedCanvas.get().getSelectedRow()+1);
				selectedCanvas.get().repaint();
			}
		
		}
	}
	
	public void cursorTToNextMeasure() {
		
		NavigableMap<Integer, Integer> tailMap = cachedMeasurePositions.tailMap(cursorT.get(), false);
		if (!tailMap.isEmpty()) {
			Entry<Integer, Integer> next = tailMap.firstEntry();			
			cursorT.set(next.getKey());			
		}
		while (cursorT.get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			viewT0.getAndIncrement();
		}
		repaintCanvases();
		
	}
	
	public void cursorTToPrevMeasure() {
		NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(cursorT.get(), false);
		if (!headMap.isEmpty()) {
			Entry<Integer, Integer> last= headMap.lastEntry();			
			cursorT.set(last.getKey());			
		}
		while (cursorT.get() < viewT0.get()+scrollTimeMargin) {
			viewT0.getAndDecrement();
		}
		repaintCanvases();
	}
		
	
	public static KiteTabSequencer getInstance() {
		if (instance == null) {
			instance = new KiteTabSequencer();
		}
		return instance;
	}
	
	private KiteTabSequencer() {		
		createGui();		
	}


	abstract class KiteTabCanvas<A> extends JPanel {
		
		protected final Map<Integer,Map<Integer,A>> data = new HashMap<>();
		AtomicInteger selectedRow = new AtomicInteger(0);		
		
		public abstract int getRowCount();
		public abstract void handleEvents(int t);
		public abstract boolean handleCharInput(char c);
		public abstract String getName();			
		
		KiteTabCanvas() {
			this.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent me) {
									
					int t_ = (int) Math.floor(me.getPoint().getX()/cellWidth)+viewT0.get();										
					int row = (int) Math.floor((me.getPoint().y-infoPanelHeight)/rowHeight);
					if (selectedCanvas.get() != null) {						
						selectedCanvas.get().repaint();
					}
					selectedCanvas.set(KiteTabCanvas.this);					
					selectedCanvas.get().setSelectedRow(row);
					if (t_ >= 0) {
												
						setCursorT(t_);
						
						if (me.isShiftDown()) {
							setPlayT();
						} else if(me.isControlDown()) {
							setStopT0();							
						} else if (me.isAltDown()) {
							adjustRepeatT();
						}
						selectedCanvas.get().repaint();
					}
				}
				
			});
		}
		
		 
		
		public final boolean isSelected() {
			return this == selectedCanvas.get(); 
		}
		
		private Color selectedLabelTextColor = Color.yellow;
		private Color unselectedLabelTextColor = Color.white;
		private Color gridColor = new Color(100,100,100);
		private Color selectedHeaderBackgroundColor = Color.black;
		private Color unselectedHeaderBackgroundColor = Color.black;
		private Color evenGridBackground = new Color(20,20,20);
		private Color oddGridBackground = new Color(30,30,30);
		private Color playTColor = new Color(50,50,50);
		private Color selectedMeasureColor = Color.LIGHT_GRAY;
		private Color unselectedMeasureColor = Color.LIGHT_GRAY;
		private Color selectedCellColor = Color.red;
		private Color repeatColor = Color.orange.darker();
							
		public final void drawGrid(Graphics2D g) {
			g.setFont(font);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(isSelected()?selectedHeaderBackgroundColor:unselectedHeaderBackgroundColor);			
			g.fillRect(0,0,getWidth(),getHeight());			  
			g.setStroke(new BasicStroke(1));			
			g.setPaint(isSelected()?selectedLabelTextColor:unselectedLabelTextColor);
			g.drawString(getName(),2,fontMetrics.getMaxAscent());			
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			
													
			{
				int y = infoPanelHeight;
			
				for (int row= 0; row < getRowCount(); row++) {
					g.setPaint(row%2==0?evenGridBackground:oddGridBackground);
					g.fillRect(0, y, getWidth(), rowHeight);						
					y+=rowHeight;
				}
				
			}
			int x = 0;
			Path2D.Double p2d = new Path2D.Double();
			IntStream.range(0, getRowCount()+1).map(a->infoPanelHeight+a*rowHeight).forEach(y ->{
				p2d.append(new Line2D.Double(0,y,getWidth(),y),false);
			});
			for (int t = t0; t < t1; t++) { 			
				p2d.append(new Line2D.Double(x,infoPanelHeight,x,infoPanelHeight+rowHeight*getRowCount()),false);
				x+=cellWidth;
			}
			
			x = 0;
			for (int t = t0; t < t1; t++) {
				if (t == playT.get()) {
					g.setPaint(playTColor);
					g.fillRect(x, infoPanelHeight, cellWidth, rowHeight*getRowCount());
				}
				g.setPaint(isSelected()?selectedMeasureColor:unselectedMeasureColor);
				g.setFont(font.deriveFont(Font.PLAIN));
								
				if (cachedMeasurePositions.containsKey(t)) {
					int measure = cachedMeasurePositions.get(t);
					g.drawString(""+measure,x,infoPanelHeight);					
				}
				if (cachedBeatMarkerPositions.contains(t)) {					
					g.drawString(".",x,infoPanelHeight);					
				}
				x+=cellWidth;
			}
			g.setPaint(gridColor);
			g.draw(p2d);
			x=0;
			
			for (int t = t0; t < t1; t++) {
				g.setPaint(gridColor);
				if (cachedMeasurePositions.containsKey(t)) {
					g.setStroke(new BasicStroke(2));
					//g.drawLine(x, y-rowHeight, x, y);
				} else {
					g.setStroke(new BasicStroke(1));
				}
				int y = rowHeight+infoPanelHeight;
				for (int row= 0; row < getRowCount(); row++) {
					if (isSelected() && row == getSelectedRow() && 
							t == cursorT.get()) {
						g.setPaint(selectedCellColor);
						g.setStroke(new BasicStroke(1));
						g.drawRect(x, y-rowHeight, cellWidth,rowHeight);
						
					}
					y+=rowHeight;
				}
				if (stopT0.get() == t) {
					g.setStroke(new BasicStroke(1));
					g.setPaint(repeatColor);
					g.drawLine(x, infoPanelHeight, x, infoPanelHeight+getRowCount()*rowHeight);					
					g.drawLine(x+3, infoPanelHeight, x+3, infoPanelHeight+getRowCount()*rowHeight);
				}
				
				if (repeatT.get() == t && t>=0) {
					g.setStroke(new BasicStroke(1));
					g.setPaint(repeatColor);					
					g.drawLine(x, infoPanelHeight, x, infoPanelHeight+getRowCount()*rowHeight);					
					g.drawLine(x-3, infoPanelHeight, x-3, infoPanelHeight+getRowCount()*rowHeight);
				}
				x+=cellWidth;
			}
		}
		
		public final int getMaxVisibleTime() {
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			return t1;
		}		
		
				
		public final Optional<A> getValueAt(int t, int row) {
			return Optional.ofNullable(data.getOrDefault(t,Collections.emptyMap()).get(row));
		}
		
		public final Optional<A> getPrecedingValue() {
			return getValueAt(cursorT.get()-1, getSelectedRow());					
		}

		public final Optional<A> getSelectedValue() {
			return getValueAt(cursorT.get(), getSelectedRow());			
		}

		public final void setSelectedValue(int t, A inputVal) {
			if (!data.containsKey(t)) {
				data.put(t, new HashMap<>());
			}
			data.get(cursorT.get()).put(getSelectedRow(), inputVal);
			
		}
		
		public final void setSelectedValue(A inputVal) {
			setSelectedValue(cursorT.get(),inputVal);		
		}					
		
		public final Optional<A> removeSelectedValue() {
			Optional<A> toReturn = Optional.ofNullable(data.getOrDefault(cursorT.get(), new HashMap<>()).get(selectedRow.get()));
			data.getOrDefault(cursorT.get(),new HashMap<>()).remove(selectedRow.get());
			return toReturn;
		}							
		
		public final int getSelectedRow() {
			return selectedRow.get();
		}
		
		public final void setSelectedRow(int row) {
			selectedRow.set(row);
		}

	}
	
	class NavigationCanvas extends JPanel implements MouseMotionListener, MouseListener {
					
		private int dataLength = 16*256;
		
		public NavigationCanvas() {
			this.addMouseMotionListener(this);
			this.addMouseListener(this);
		}
		
		
		@Override
		public Dimension getSize() {				 
			return new Dimension(super.getWidth(),20);
		}
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setPaint(Color.BLACK);
			g.fill(getBounds());				
			double t0 = viewT0.get();
			
			double tDelta = getWidth()/cellWidth;
			double t1 = t0+tDelta;
			double x0 = t0/dataLength*getWidth();
			double x1 = t1/dataLength*getWidth();
			g.setPaint(Color.WHITE);
			g.draw(new Rectangle2D.Double(x0,getBounds().y,x1-x0-1,getHeight()-1));
		}
		

		
		Integer pressedX = 0;
		
		@Override
		public void mousePressed(MouseEvent me) {
			pressedX = me.getPoint().x;
		}
		
		@Override
		public void mouseDragged(MouseEvent me) {
			if (pressedX == null) {
				System.err.println("wtf");
				return;
			}
			
			JPanel navigationBar = (JPanel) me.getSource();
			double deltaX = pressedX - me.getPoint().x;
			pressedX = me.getPoint().x;
			int deltaT= (int) (deltaX/navigationBar.getWidth()*dataLength);
			double t0 = viewT0.get();				 				
			double displayedt1 = t0+navigationBar.getWidth()/cellWidth;
			viewT0.updateAndGet(a->Math.min(dataLength-(int)(displayedt1-t0), Math.max(0,a-deltaT)));			
			repaintCanvases();			 
		}
			
		@Override
		public void mouseReleased(MouseEvent me) {
			pressedX = null;
		}
		
		@Override
		public void mouseClicked(MouseEvent e) {}
		@Override
		public void mouseEntered(MouseEvent e) {}
		@Override
		public void mouseExited(MouseEvent e) {}
		@Override
		public void mouseMoved(MouseEvent e) {}
	}

	class EventCanvas extends KiteTabCanvas<ControlEvent> {
				
		public EventCanvas() {}

		public Optional<TimeSignatureEvent> getTimeSignatureEventForTime(int t_) {
			return data.getOrDefault(t_, Collections.emptyMap()).values().stream()
					.filter(a->a instanceof TimeSignatureEvent).map(a->(TimeSignatureEvent)a)
					.findFirst();
		}

		@Override
		public int getRowCount() {
			return 3;
		}		

		@Override
		public String getName() {
			return "Events";
		}		
		
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			drawGrid(g);			
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;			
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 					
				g.setFont(font);
				
				for (int rowNumber = 0; rowNumber < getRowCount(); rowNumber++) {
					
					g.setPaint(Color.black);
					
					ControlEvent event = data.getOrDefault(t_,new HashMap<>()).getOrDefault(rowNumber,NilControlEvent.get());
					switch (event.getType()) {
					case NIL:						
						break;
					case TIME_SIGNATURE:
						g.setFont(font.deriveFont(Font.ITALIC));
						g.setPaint(Color.YELLOW);
						g.drawString(event.toString(),x+1,y-3);
						g.setFont(font);
						break;
					case TEMPO:
						g.setFont(font.deriveFont(Font.ITALIC));
						g.setPaint(Color.pink);
						g.drawString(event.toString(),x+1,y-3);
						g.setFont(font);
						break;
					case STICKY_NOTE:
						g.setFont(font.deriveFont(Font.ITALIC));						
						
						g.drawString(((StickyNote) event).getText(),x+1,y-3);
						
					default:
						break;					
					}					
					y+=rowHeight;
				}				
				x+=cellWidth;
			}
		}

		@Override
		public void handleEvents(int t) {
			for (int row = 0; row < getRowCount(); row++) {
				Optional<ControlEvent> controlEvent= this.getValueAt(t, row);
				controlEvent.filter(a->a.getType()==ControlEventType.TEMPO)
				.ifPresent(event -> {
					tempo.set(((TempoEvent) event).getTempo());
				});
			}			
		}

		@Override
		public boolean handleCharInput(char c) {
			
			switch (c) {
			case 'T':
				addTimeSignature();
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				return true;
			case 'S':
				addTempo();
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				return true;
			case 'N':
				addStickyNote();
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				return true;
			default:
				return false;				
			}
			
		}
	}
	
	class TabCanvas extends KiteTabCanvas<String> implements SoundfontPlayer {		
		
		private final String name;
		private final double[] baseFrequencies;	
		private final int rows;

		private final Map<Integer,String> openNotes = new ConcurrentHashMap<>();
		private final Map<MidiChannel,Integer> openMidiNums = new ConcurrentHashMap<>();
		
		Synthesizer synth = null;
		private File soundfontFile = defaultSoundfontFile;
		private final AtomicReference<Instrument> loadedInstrument = new AtomicReference<>(null);
		private final StringCanvasConfig canvasConfig;
				
		public TabCanvas(StringCanvasConfig config) {
			this.canvasConfig = config;
			this.rows = config.getFrequencies().length;
			this.name = config.getName();
			this.baseFrequencies = config.getFrequencies();
			initializeMidi(config.getSoundfontFile().orElse(defaultSoundfontFile),
					config.getBank(),config.getProgram());
		}

		public Synthesizer getSynth() {
			return synth;
		}
				
		public void displayInstrumentDialog() {
			JDialog dialog = new JDialog(frame,String.format("Configuring instruments for [%s]",
					getName()));
			dialog.setModalityType(ModalityType.APPLICATION_MODAL);
			JButton loadSoundfontButton = new JButton("Load Soundbank:");
			JTextField soundbankTextField = new JTextField(20);
			
			JList<Instrument> instrumentList = new JList<>();
			JScrollPane instrumentScrollPane = new JScrollPane(instrumentList,JScrollPane.VERTICAL_SCROLLBAR_ALWAYS, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
			
			soundbankTextField.setEditable(false);
			
			ListCellRenderer<? super Instrument> originalRenderer = instrumentList.getCellRenderer();
			
			instrumentList.setCellRenderer((list,value,index,isSelected,hasFocus) ->{
				JLabel label = (JLabel) originalRenderer.getListCellRendererComponent(list, value, index, isSelected, hasFocus);				
				 label.setText(String.format("%s (bank %d, program %d)",
						value.getName(),
						value.getPatch().getBank(),
						value.getPatch().getProgram()));
				if (value == loadedInstrument.get()) {
					label.setFont(label.getFont().deriveFont(Font.BOLD | Font.ITALIC));
				}
				return label;

			});
			
			loadSoundfontButton.addActionListener(ae -> {
				JFileChooser chooser = new JFileChooser("sf2");
				chooser.setFileFilter(new FileNameExtensionFilter("Soundfont files (.sf2)","sf2"));
				if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {					
					soundfontFile = chooser.getSelectedFile();
					soundbankTextField.setText(soundfontFile.getName());
					try {
						DefaultListModel<Instrument> model = new DefaultListModel<>();
						model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
						instrumentList.setModel(model);						
					} catch (Exception ex) {
						ex.printStackTrace();
					}				
				}
			});
			
			if (soundfontFile != null) {
				soundbankTextField.setText(soundfontFile.getName());
				try {
					DefaultListModel<Instrument> model = new DefaultListModel<>();
					model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
					instrumentList.setModel(model);
				} catch (Exception ex) {
					ex.printStackTrace();
				}				
			} else {
				soundbankTextField.setText("<default>");
				try {
					DefaultListModel<Instrument> model = new DefaultListModel<>();
					model.addAll(Arrays.asList(synth.getLoadedInstruments()));					
					instrumentList.setModel(model);
				} catch (Exception ex) {
					ex.printStackTrace();
				}
			}
			
			loadSoundfontButton.addActionListener(ae -> {
				JFileChooser chooser = new JFileChooser("sf2");
				if (soundfontFile != null) {
					chooser.setSelectedFile(soundfontFile);
					if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
						soundfontFile = chooser.getSelectedFile();
						soundbankTextField.setText(soundfontFile.getName());
						try {
							DefaultListModel<Instrument> model = new DefaultListModel<>();
							model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
							instrumentList.setModel(model);
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					}
				}
			});
			
			JButton loadInstrumentButton = new JButton("Load instrument");
			loadInstrumentButton.addActionListener(ae ->{				
				Instrument instrument = instrumentList.getSelectedValue();
				loadedInstrument.set(instrument);
				initializeMidi(soundfontFile,instrument.getPatch().getBank(),instrument.getPatch().getProgram());
				repaint();
			});
			
			JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEADING));
			topPanel.add(loadSoundfontButton);
			topPanel.add(soundbankTextField);
			 
			dialog.getContentPane().add(topPanel,BorderLayout.NORTH);
			dialog.getContentPane().add(instrumentScrollPane,BorderLayout.CENTER);
			dialog.getContentPane().add(loadInstrumentButton,BorderLayout.SOUTH);
			
			dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
			dialog.setLocationRelativeTo(null);
			dialog.pack();
			dialog.setVisible(true);	
		}


		public void noteOff(int row) {
			openNotes.remove(row);
			processTabCanvasEvent(row,null);
		}
		
		public void noteOn(int row,String inputVal) {
			openNotes.put(row, inputVal);
			processTabCanvasEvent(row,inputVal);
		}
		
		void processTabCanvasEvent(int row,String inputVal) {
			MidiChannel channel = synth.getChannels()[row<9?row:row+1]; 			
			if (inputVal == null) {
				if (openMidiNums.containsKey(channel)) {
					channel.noteOff(openMidiNums.get(channel));
					openMidiNums.remove(channel);
				}			
			}
			
		}
		
		public double[] getBaseFrequencies() {
			return baseFrequencies;
		}
				
		@Override
		public Dimension getSize() {				 			
			return new Dimension(super.getWidth(),infoPanelHeight + rowHeight * rows);
		}		
		 
		@Override
		public void paint(Graphics g_) {
			
			Graphics2D g = (Graphics2D) g_;
			drawGrid(g);
			
			g.setFont(font.deriveFont(Font.ITALIC));
			g.setPaint(Color.WHITE);
			g.drawString(String.format("(loaded instrument: '%s')",
					loadedInstrument.get() == null ? "<null>" : loadedInstrument.get().getName()),					
					12+fontMetrics.stringWidth(getName()),fontMetrics.getMaxAscent());
			
			g.setPaint(Color.BLACK);
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 
				g.setFont(font);
				for (int row = 0; row< rows; row++) {
					g.setPaint(Color.WHITE);									
					String s = data.getOrDefault(t_,new HashMap<>()).getOrDefault(row,"");					
					g.drawString(s,(int) (x+(cellWidth-fontMetrics.stringWidth(s))*0.5),y-3);
					y+=rowHeight;
				}
				x+=cellWidth;
			}
		}

		@Override
		public int getRowCount() {			
			return rows;
		}

		@Override
		public String getName() {
			return name;
		}

		@Override
		public void handleEvents(int t) {
			
			BiConsumer<Integer,Double> f = (row, freq) -> {
				MidiChannel channel = synth.getChannels()[row<9?row:row+1];
				double n = (12 * Math.log(freq/440)/Math.log(2) + 69);
				int midiNote = (int) Math.round(n);
				double semitoneOffset = n - midiNote;
				final double semitoneRange = 2.0;
				double bendRatio = semitoneOffset/ semitoneRange;
				int pitchBend = 8192 + (int) (bendRatio*8192);
				pitchBend = Math.max(0, Math.min(16383, pitchBend));
				int lsb = pitchBend & 0x7F;
				int msb = (pitchBend >> 7) & 0x7F;
				try {
					ShortMessage pb = new ShortMessage();					
					pb.setMessage(ShortMessage.PITCH_BEND, row, lsb, msb);
					synth.getReceiver().send(pb, -1);
					channel.noteOn(midiNote, 100);
					openMidiNums.put(channel,midiNote);
			 	} catch (Exception ex) {
			 		ex.printStackTrace();
			 	}
			};
			IntStream.range(0, getRowCount()).forEach(row -> {
				MidiChannel channel = synth.getChannels()[row<9?row:row+1];
				double baseFreq = canvasConfig.getFrequencies()[row];
				 
				this.getValueAt(t, row).ifPresentOrElse(inputVal -> {
					if (inputVal.charAt(0) != '-') {
						//
						if (openMidiNums.containsKey(channel)) {
							channel.noteOff(openMidiNums.get(channel));
							openMidiNums.remove(channel);
						}
						
						if (inputVal.charAt(0) == 'H') {
							double freq = baseFreq;
							freq*=Integer.parseInt(inputVal.substring(1));
							f.accept(row, freq);
							
						} else if (canvasConfig.getAdditionalPitchMap().containsKey(inputVal)){
							
							double edoSteps = canvasConfig.getAdditionalPitchMap().get(inputVal);
							double freq = baseFreq*Math.pow(2, edoSteps/canvasConfig.getEd2());
							f.accept(row, freq);
						} else {
							double edoSteps = Double.parseDouble(inputVal);
							double freq = baseFreq*Math.pow(2, edoSteps/canvasConfig.getEd2());
							f.accept(row, freq);
						}					
					}
				}, () -> {
					noteOff(row);
				});
			});
			
			
		}

		
		
		@Override
		public void initializeMidi(File file, int bank, int program) {
			try {
				this.soundfontFile = file;
				if (synth == null) {
					synth = MidiSystem.getSynthesizer();
				} 
				if (!synth.isOpen()) {
					synth.open();
				}
				if (this.soundfontFile != null) {
					Soundbank soundbank = MidiSystem.getSoundbank(soundfontFile);
					synth.unloadAllInstruments(synth.getDefaultSoundbank());
					Stream.of(soundbank.getInstruments()).filter(a->
						a.getPatch().getBank() == bank && a.getPatch().getProgram() == program)
					.findFirst().ifPresent(instrument -> {
						loadedInstrument.set(instrument);
						synth.loadInstrument(instrument);
					});				
				}
				
				for (int row = 0; row < getRowCount(); row++) {				
					synth.getChannels()[row<9?row:row+1].programChange(bank,program);
				}
				
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		
			
		}

		@Override
		public void silence() {
			for (int row = 0; row < getRowCount(); row++) {
				MidiChannel channel = synth.getChannels()[row<9?row:row+1];
				channel.allSoundOff();
			}
			
		}

		Pattern harmonicPattern = Pattern.compile("^H(\\d*)$");
		Pattern integerPattern = Pattern.compile("^(\\d+)$");
		
		@Override
		public boolean handleCharInput(char c) {
			if (c == '-') {
				{
					int t = cursorT.get()+1;
					
					while (getValueAt(t,getSelectedRow()).orElse("").equals("-")) {
						System.out.println(t+" "+getValueAt(t,getSelectedRow()).orElse("").contentEquals("-"));
						data.getOrDefault(t,Collections.emptyMap()).remove(getSelectedRow());
						t++;
					}
					
				}

				for (int t = cursorT.get()-1; t>=0; t--) {
					
					if (getValueAt(t,getSelectedRow()).isPresent()) {
						for (int t2 = t+1; t2<cursorT.get(); t2++) {
							data.putIfAbsent(t2, new HashMap<>());
							data.get(t2).put(getSelectedRow(),"-");
						}						
						
					}
				}
				setSelectedValue(String.valueOf(c));
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				repaint();
				return true;
			}
			
			String token0 = getValueAt(cursorT.get(),getSelectedRow()).orElse("");
			String token1 = token0+String.valueOf(c);
			
			Predicate<String> isValid = token -> {
				Matcher harmonicMatcher = harmonicPattern.matcher(token);
				if (harmonicMatcher.find() && (harmonicMatcher.group(1).isBlank() || 
						Integer.parseInt(harmonicMatcher.group(1)) <= canvasConfig.getMaxHarmonic())) {
					
					return true;
				}
				Matcher integerMatcher = integerPattern.matcher(token);
				if (integerMatcher.find() && Integer.parseInt(token) <= canvasConfig.getMaxFrets()) {
					return true;
				}
				if (canvasConfig.getAdditionalPitchMap().keySet().contains(token)) {
					return true;
				}
				return false;				
			};
			
			if (isValid.test(token1)) {
				setSelectedValue(token1);
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				repaint();
				return true;
			} 
			if (isValid.test(String.valueOf(c))) {
				setSelectedValue(String.valueOf(c));
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				repaint();
				return true;
			}
			
			return false;
		}
	}
	
	interface SoundfontPlayer {
		public void initializeMidi(File file, int bank, int program);
		public void silence();
		public Synthesizer getSynth();
		
	}
	
	class DrumTabCanvas extends KiteTabCanvas<PercRowToken> implements SoundfontPlayer {

		Synthesizer synth = null;
				
		private final AtomicReference<Instrument> loadedInstrument = new AtomicReference<>(null);
		private final DrumCanvasConfig canvasConfig;
		public Synthesizer getSynth() {
			return synth;
		}
		
		public DrumTabCanvas(DrumCanvasConfig canvasConfig) {
			this.canvasConfig = canvasConfig;
			initializeMidi(canvasConfig.getSoundfontFile().orElse(defaultSoundfontFile),
					canvasConfig.getBank(),canvasConfig.getProgram());
			//initializeMidi(defaultSoundfontFile,0,0);
		}
		
		@Override
		public void initializeMidi(File file, int bank, int program) {
			try {
				synth = MidiSystem.getSynthesizer();
				synth.open();
				
				Soundbank soundbank = file == null ? synth.getDefaultSoundbank() : MidiSystem.getSoundbank(file);
				
				List<Instrument> percussionInstruments = new ArrayList<>();
				
				for (Instrument instrument : soundbank.getInstruments()) {
					boolean bank128 = instrument.getPatch().getBank() == 128;
					boolean matchesRegex = instrument.getName().toLowerCase().contains("drum") ||
							instrument.getName().toLowerCase().contains("perc");
					if (bank128 || matchesRegex) {
						percussionInstruments.add(instrument);
					}
				}	
				
				percussionInstruments.stream().filter(a->
				a.getPatch().getBank() == bank && a.getPatch().getProgram() == program)
				.findFirst().ifPresent(instrument -> {					
					loadedInstrument.set(instrument);					
					synth.loadInstrument(instrument);
					synth.getChannels()[9].programChange(bank, program);
				});				
					
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
		
		private final Set<Integer> openNotes = new HashSet<>();
		
		@Override
		public void handleEvents(int t) {
			MidiChannel channel = synth.getChannels()[9];
			openNotes.forEach(channel::noteOff);
			openNotes.clear();
			
			this.data.getOrDefault(t,Collections.emptyMap())
			.values().stream().distinct().forEach(drumVal -> {				
				channel.noteOn(drumVal.getMidiNumber(), 100);
				openNotes.add(drumVal.getMidiNumber());
			});			
			
			
		}

		@Override
		public void paint(Graphics g_) {
			
			Graphics2D g = (Graphics2D) g_;
			drawGrid(g);
			
			g.setFont(font.deriveFont(Font.ITALIC));
			g.setPaint(Color.WHITE);
			g.drawString(String.format("(loaded instrument: '%s')",
					loadedInstrument.get() == null ? "<null>" : loadedInstrument.get().getName()),					
					12+fontMetrics.stringWidth(getName()),fontMetrics.getMaxAscent());
						
			int t0 = viewT0.get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;			
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 												
				g.setFont(font);
				for (int row= 0; row < getRowCount(); row++) {																								
					g.setPaint(Color.WHITE);
					int y_ = y;
					int x_ = x;
					this.getValueAt(t_, row).map(a->a.getToken()).ifPresent(s->{
						g.drawString(s,(int) (x_+(cellWidth-fontMetrics.stringWidth(s))*0.5),y_-3);	
					});

					y+=rowHeight;
				}				
				x+=cellWidth;
			}			
		}

		@Override
		public int getRowCount() {
			return canvasConfig.getRowTypes().size();
		}

		@Override
		public String getName() {
			return canvasConfig.getName();
		}
		@Override
		public void silence() {
			MidiChannel channel = synth.getChannels()[9];
			channel.allSoundOff();
			
			
		}

		@Override
		public boolean handleCharInput(char c) {
			
			Optional<PercRowToken> tokenO = getValueAt(cursorT.get(),getSelectedRow());			
			Predicate<PercRowToken> permitted = a->a.getPosition() == canvasConfig.getRowTypes().get(getSelectedRow());
			if (tokenO.isPresent()) {
				Optional<PercRowToken> newTokenO = 
						canvasConfig.getTokens().stream()
						.filter(a->a.getToken().equals(tokenO.get().getToken()+String.valueOf(c).toUpperCase()))
						.filter(permitted).findFirst();
				if (newTokenO.isPresent()) {					
					setSelectedValue(cursorT.get(),newTokenO.get());
					repaint();
					return true;
				}				
			}	
			
			Optional<PercRowToken> newTokenO = 
					canvasConfig.getTokens().stream().filter(a->a.getToken().equals(String.valueOf(c).toUpperCase()))
					.filter(permitted).findFirst();
			
			if (newTokenO.isPresent()) {					
				setSelectedValue(cursorT.get(),newTokenO.get());
				repaint();
				return true;
			}			
			return false;
		}
	}
	
	
	void initializeCanvases() {
		try {
			CanvasesConfig config = CanvasesConfig.getXMLInstance();
			for (CanvasConfig canvasConfig : config.getCanvases()) {
				switch (canvasConfig.getType()) {
				case DRUM: {
					DrumCanvasConfig drumConfig = (DrumCanvasConfig) canvasConfig;					
					DrumTabCanvas canvas = new DrumTabCanvas(drumConfig);
					allCanvases.add(canvas);
					allSoundPlayers.add(canvas);
					break;
				}
				case STRING:{
					StringCanvasConfig stringConfig = (StringCanvasConfig) canvasConfig;
					TabCanvas canvas = new TabCanvas(stringConfig);
					allCanvases.add(canvas);
					allSoundPlayers.add(canvas);
					break;
				}
				default:
					break;
				
				}
			}
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		
	}
	
	void createGui() {
		initializeCanvases();
		Box mainPanel = Box.createVerticalBox();
		InputMap inputMap = mainPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		ActionMap actionMap = mainPanel.getActionMap();
		
		KeyStroke k_CtrlI = KeyStroke.getKeyStroke("control I");
		KeyStroke k_ctrlO = KeyStroke.getKeyStroke("control O");
		KeyStroke k_ctrlS = KeyStroke.getKeyStroke("control S");
		KeyStroke k_ctrlR = KeyStroke.getKeyStroke("control R");
		KeyStroke k_Up = KeyStroke.getKeyStroke("UP");
		KeyStroke k_Down = KeyStroke.getKeyStroke("DOWN");
		KeyStroke k_ShiftUp = KeyStroke.getKeyStroke("shift UP");
		KeyStroke k_ShiftDown = KeyStroke.getKeyStroke("shift DOWN");
		KeyStroke k_CtrlShiftLeft = KeyStroke.getKeyStroke("control shift LEFT");
		KeyStroke k_CtrlShiftRight= KeyStroke.getKeyStroke("control shift RIGHT");
		KeyStroke k_CtrlLeft = KeyStroke.getKeyStroke("control LEFT");
		KeyStroke k_CtrlRight= KeyStroke.getKeyStroke("control RIGHT");
		KeyStroke k_Left = KeyStroke.getKeyStroke("LEFT");
		KeyStroke k_ShiftLeft = KeyStroke.getKeyStroke("shift LEFT");
		KeyStroke k_Right = KeyStroke.getKeyStroke("RIGHT");
		KeyStroke k_ShiftRight= KeyStroke.getKeyStroke("shift RIGHT");
		
		KeyStroke k_ShiftSpace = KeyStroke.getKeyStroke("shift SPACE");
		KeyStroke k_CtrlSpace = KeyStroke.getKeyStroke("ctrl SPACE");
		KeyStroke k_ShiftCtrlSpace = KeyStroke.getKeyStroke("shift ctrl SPACE");
		KeyStroke k_Space = KeyStroke.getKeyStroke("SPACE");
		KeyStroke k_Backspace = KeyStroke.getKeyStroke("BACK_SPACE");
		KeyStroke k_Delete= KeyStroke.getKeyStroke("DELETE");
		KeyStroke k_Period = KeyStroke.getKeyStroke("PERIOD");
		KeyStroke k_Comma= KeyStroke.getKeyStroke("COMMA");
		KeyStroke k_Hyphen= KeyStroke.getKeyStroke("MINUS");
		
		
		inputMap.put(k_Hyphen, "hyphen");
		actionMap.put("hyphen",rToA(()->this.handleABCInput('-')));
		inputMap.put(k_CtrlI, "configureCanvasInstrument");
		actionMap.put("configureCanvasInstrument",rToA(this::configureCanvasInstrument));
		
		inputMap.put(k_Period, "addStaccato");
		actionMap.put("addStaccato", rToA(this::addStaccato));
		inputMap.put(k_Comma, "addAccent");
		actionMap.put("addAccent", rToA(this::addAccent));
			
		inputMap.put(k_ctrlO, "openFileDialog");
		actionMap.put("openFileDialog", rToA(this::openFileDialog));
		inputMap.put(k_ctrlS, "saveActiveFile");
		actionMap.put("saveActiveFile", rToA(this::saveActiveFile));
		inputMap.put(k_ctrlR, "adjustRepeatT");
		actionMap.put("adjustRepeatT", rToA(this::adjustRepeatT));		
		inputMap.put(k_ShiftSpace, "setPlayT");
		actionMap.put("setPlayT", rToA(() -> this.setPlayT()));
		inputMap.put(k_CtrlSpace, "returnToStopT0");
		actionMap.put("returnToStopT0", rToA(() -> this.returnToStopT0()));
		inputMap.put(k_Space, "togglePlayStatus");
		actionMap.put("togglePlayStatus", rToA(() -> this.togglePlayStatus()));
		inputMap.put(k_ShiftCtrlSpace, "setStopT0");
		actionMap.put("setStopT0", rToA(() -> this.setStopT0()));
		
		//inputMap.put(k_Period, "toggleNoteSelectionMode");
		//actionMap.put("toggleNoteSelectionMode", rToA.apply(()->this.toggleNoteSelectionMode()));
		inputMap.put(k_Backspace, "backspace");
		actionMap.put("backspace",rToA(this::backspace));
		inputMap.put(k_Delete, "delete");
		actionMap.put("delete",rToA(this::backspace));
		inputMap.put(k_CtrlShiftLeft, "ctrlShiftLeft");
		actionMap.put("ctrlShiftLeft", rToA(this::playTToPreviousMeasure));
		inputMap.put(k_CtrlShiftRight, "ctrlShiftRight");
		actionMap.put("ctrlShiftRight", rToA(this::playTToNextMeasure));
		inputMap.put(k_CtrlLeft, "ctrlLeft");
		actionMap.put("ctrlLeft", rToA(this::decrementPlayT));
		inputMap.put(k_CtrlRight, "ctrlRight");
		actionMap.put("ctrlRight", rToA(this::incrementPlayT));
		inputMap.put(k_Up, "up");
		actionMap.put("up", rToA(this::arrowUp));
		inputMap.put(k_Down, "down");
		actionMap.put("down", rToA(this::arrowDown));
		inputMap.put(k_ShiftUp, "shiftUp");
		actionMap.put("shiftUp", rToA(this::shiftArrowUp));
		inputMap.put(k_ShiftDown, "shiftDown");
		actionMap.put("shiftDown", rToA(this::shiftArrowDown));
		inputMap.put(k_Right, "right");
		actionMap.put("right", rToA(this::incrementCursorT));	
		inputMap.put(k_Left, "left");
		actionMap.put("left", rToA(this::decrementCursorT));
		inputMap.put(k_ShiftRight, "shiftRight");;
		actionMap.put("shiftRight", rToA(this::cursorTToNextMeasure));
		inputMap.put(k_ShiftLeft, "shiftLeft");;
		actionMap.put("shiftLeft", rToA(this::cursorTToPrevMeasure));
		
		for (int i = 'A'; i <= 'Z'; i++) {
			final int i_ = i;
			String s = String.valueOf((char) i_);
			KeyStroke k = KeyStroke.getKeyStroke(s);
			inputMap.put(k, ""+i_);
			actionMap.put(""+i_,rToA(()->this.handleABCInput((char) i_)));
		}
		
		for (int i = 0; i < 10; i++) {
			final int i_ = i;
			KeyStroke k = KeyStroke.getKeyStroke(""+i);
			inputMap.put(k, ""+i_);
			actionMap.put(""+i_,rToA(()->this.handleABCInput((""+i_).charAt(0))));
		}
		
		Box controlBar = Box.createHorizontalBox();
		//controlBar.setLayout(null);
		
		playButton = new LeftClickablePanelButton(this::startPlayback) {
			@Override
			public void paint(Graphics g_) {
				
				Graphics2D g = (Graphics2D) g_;
				
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				g.setPaint(Color.green);
				g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,10,10);
				g.setPaint(Color.black);
				
				double r = 0.3*(Math.min(getHeight(),getWidth()));
				double cx = getWidth()/2.0;
				double cy = getHeight()/2.0;
				
				Path2D.Double p2d = new Path2D.Double();
				p2d.moveTo(cx+r*Math.cos(0),cy+Math.sin(0));				
				p2d.lineTo(cx+r*Math.cos(Math.toRadians(120)),cy+r*Math.sin(Math.toRadians(120)));
				p2d.lineTo(cx+r*Math.cos(Math.toRadians(240)),cy+r*Math.sin(Math.toRadians(240)));				
				p2d.closePath();
				
				g.fill(p2d);
			}
		};
		
				
		stopButton = new LeftClickablePanelButton(this::stopPlayback) {
			@Override
			public void paint(Graphics g_) {
				Graphics2D g = (Graphics2D) g_;
				g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
				g.setPaint(Color.RED);
				g.fillRoundRect(1,1,getWidth()-2,getHeight()-2,10,10);
				g.setPaint(Color.black);				
				g.fill(new Rectangle2D.Double(getWidth()*0.25, getHeight()*0.25, getWidth()*0.5, getHeight()*0.5));				
			}
		};
		
		stopButton.setMaximumSize(new Dimension(30,230));
		stopButton.setMinimumSize(new Dimension(30,30));
		playButton.setMaximumSize(new Dimension(30,30));
		playButton.setMinimumSize(new Dimension(30,30));
		for (Component c : Arrays.asList(playButton,stopButton,playStatusPanel)) {
			controlBar.add(c);
		}
		controlBar.add(Box.createHorizontalGlue());
		
		navigationBar.setMaximumSize(new Dimension(Integer.MAX_VALUE,20));
		navigationBar.setMinimumSize(new Dimension(0,20));
		navigationBar.setPreferredSize(new Dimension(Toolkit.getDefaultToolkit().getScreenSize().width,20));
		
		mainPanel.add(navigationBar);
		for (KiteTabCanvas<?> canvas : allCanvases) {
			int h = (int) canvas.getRowCount()*rowHeight+50;
			canvas.setMaximumSize(new Dimension(Integer.MAX_VALUE,h));
			canvas.setMinimumSize(new Dimension(0,h));
			canvas.setPreferredSize(new Dimension(Toolkit.getDefaultToolkit().getScreenSize().width,h));
			mainPanel.add(canvas);
		}
		mainPanel.add(Box.createVerticalGlue());
		//panel.setLayout(null);
		/*
		int y = 0;		
		mainPanel.add(navigationBar);
		navigationBar.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,20));
		y+=navigationBar.getBounds().height;
		mainPanel.add(eventCanvas);
		eventCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,80));
		y+=eventCanvas.getBounds().height;
		
		panel.add(bassCanvas);						
		bassCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,130));
		y+=bassCanvas.getBounds().height;		
		panel.add(guitarCanvas);						
		guitarCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,150));
		y+=guitarCanvas.getBounds().height;
		panel.add(acousticCanvas);						
		acousticCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,130));
		y+=acousticCanvas.getBounds().height;
		panel.add(drumCanvas);						
		drumCanvas.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,100));
		y+=drumCanvas.getBounds().height;
		*/
		//panel.add(controlBar);
		//controlBar.setBounds(new Rectangle(0,y,Toolkit.getDefaultToolkit().getScreenSize().width,20));
		//y+=controlBar.getBounds().height;
		//y+=80;
				
		updateMeasureLinePositions();		
		//mainPanel.setPreferredSize(new Dimension(
			//	(int) drumCanvas.getBounds().getMaxX(),
				//(int) drumCanvas.getBounds().getMaxY()));
		
		JScrollPane scrollPane = new JScrollPane(mainPanel,JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		frame.getContentPane().add(scrollPane,BorderLayout.CENTER);
		frame.getContentPane().add(controlBar,BorderLayout.SOUTH);
		frame.pack();
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}
	
	
	void addTempo() {
		JDialog dialog = new JDialog(frame,String.format("Adding tempo (t = %d)",cursorT.get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		
		JLabel tempoLabel = new JLabel("Tempo");
		JSpinner tempoSpinner = new JSpinner(new SpinnerNumberModel(120,1,1000,1));
		
		Runnable r = () -> {
			TempoEvent tempo = new TempoEvent((int) tempoSpinner.getValue());
			eventCanvas.setSelectedValue(tempo);
			eventCanvas.repaint();
			dialog.dispose();
		};
		tempoSpinner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		tempoSpinner.getActionMap().put("enterPressed",rToA(r));
		
		dialog.getContentPane().add(tempoLabel,BorderLayout.WEST);
		dialog.getContentPane().add(tempoSpinner,BorderLayout.CENTER);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocationRelativeTo(null);
		
		dialog.pack();
		dialog.setVisible(true);
	}
	
	void addTimeSignature() {
		if (selectedCanvas.get() != eventCanvas) {
			return;
		}
		JDialog dialog = new JDialog(frame,String.format("Adding time signature (t = %d)",cursorT.get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		
		JPanel outerBox = new JPanel(new GridLayout(2,0));
		JLabel numeratorLabel = new JLabel("Numer");
		JLabel denominatorLabel = new JLabel("Denom");
		JSpinner numeratorSpinner = new JSpinner(new SpinnerNumberModel(4,1,Integer.MAX_VALUE,1));
		JComboBox<TimeSignatureDenominator> denominatorComboBox = new JComboBox<>(TimeSignatureDenominator.values());
		denominatorComboBox.setEditable(false);
		Runnable r = () -> {
			TimeSignatureEvent event = new TimeSignatureEvent((int) numeratorSpinner.getValue(),(TimeSignatureDenominator) denominatorComboBox.getSelectedItem());
			eventCanvas.setSelectedValue(event);
			updateMeasureLinePositions();
			repaintCanvases();
			dialog.dispose();
		};
		((JSpinner.DefaultEditor) numeratorSpinner.getEditor()).getTextField().addActionListener(ae -> r.run());
		denominatorComboBox.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		denominatorComboBox.getActionMap().put("enterPressed",rToA(r));
		numeratorSpinner.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		numeratorSpinner.getActionMap().put("enterPressed",rToA(r));
		
		denominatorComboBox.setSelectedItem(TimeSignatureDenominator._4);
		outerBox.add(numeratorLabel);
		outerBox.add(numeratorSpinner);		
		outerBox.add(denominatorLabel);
		outerBox.add(denominatorComboBox);				
		
		dialog.getContentPane().add(outerBox,BorderLayout.CENTER);		
		
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocation(MouseInfo.getPointerInfo().getLocation());
		dialog.pack();
		dialog.setVisible(true);

	}
	
	void addStickyNote() {
		JDialog dialog = new JDialog(frame,String.format("Adding sticky note (t = %d)",cursorT.get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);		
		
		JPanel topPanel = new JPanel();		
		JLabel textLabel = new JLabel("Text");

		
		JTextField textField = new JTextField("New note");
		
		eventCanvas.getSelectedValue().filter(a->a instanceof StickyNote).map(a->(StickyNote)a).ifPresent(stickyNote -> {
			textField.setText(stickyNote.getText());
		});
		AtomicReference<Color> color = new AtomicReference<>(Color.BLACK);
		
		JButton colorButton = new JButton("Color") {
			@Override
			public void paint(Graphics g_) {
				Graphics2D g = (Graphics2D) g_;
				Color c = color.get() == null ? Color.white :color.get();
				g.setPaint(c);
				g.fillRect(0,0,getWidth(),getHeight());
				float[] hsb = new float[3];
				Color.RGBtoHSB(c.getRed(), c.getGreen(), c.getBlue(), hsb);
				g.setPaint(hsb[2]>0.5?Color.BLACK:Color.WHITE);
				g.drawString("Color",2,getHeight()-1);				
			}
		};

		Runnable chooseColor = () -> {
			Color c = JColorChooser.showDialog(frame, "Choose color",color.get());
			if (c != null) {
				color.set(c);
			}
			colorButton.repaint();
		};
		colorButton.addActionListener(ae ->{
			chooseColor.run();
		});
		colorButton.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		colorButton.getActionMap().put("enterPressed",rToA(chooseColor));
		
		topPanel.add(textLabel,BorderLayout.WEST);
		topPanel.add(textField,BorderLayout.CENTER);
		//topPanel.add(colorButton,BorderLayout.EAST);
				
		dialog.getContentPane().add(topPanel,BorderLayout.CENTER);
		
		Runnable r = () -> {
		
			StickyNote event = new StickyNote(textField.getText().trim());
			eventCanvas.setSelectedValue(event);
			eventCanvas.repaint();
			dialog.dispose();
		};
		textField.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
		.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
		textField.getActionMap().put("enterPressed",rToA(r));		
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocation(MouseInfo.getPointerInfo().getLocation());
		dialog.pack();
		dialog.setVisible(true);

	}
	
	void loadXML(File file) throws Exception {
		SchemaFactory schemaF = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
		Schema schema = schemaF.newSchema(new File("schemas/projectFiles.xsd"));
		
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newDefaultInstance();
		dbf.setNamespaceAware(true);
		dbf.setSchema(schema);
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(file);
		Element root = doc.getDocumentElement();
		if (root.hasAttribute("cursorT")) {
			cursorT.set(Integer.parseInt(root.getAttribute("cursorT")));
		}
		if (root.hasAttribute("stopT")) {
			stopT0.set(Integer.parseInt(root.getAttribute("stopT")));
		}
		if (root.hasAttribute("selectedCanvas")) {
			try {
				selectedCanvas.set(
						allCanvases.get(Integer.parseInt(root.getAttribute("selectedCanvas"))));
			} catch (Exception ex) {
				selectedCanvas.set(eventCanvas);				
			}
			
		}
		if (root.hasAttribute("selectedRow")) {
			selectedCanvas.get().setSelectedRow(Integer.parseInt(root.getAttribute("selectedRow")));
		}
		if (root.hasAttribute("repeatT")) {
			repeatT.set(Integer.parseInt(root.getAttribute("repeatT")));
		}
		
		NodeList eventCanvasNodes = root.getElementsByTagName("eventCanvas") ;
		Element eventCanvasNode = (Element) eventCanvasNodes.item(0);
		NodeList eventCanvasEventNodes = eventCanvasNode.getElementsByTagName("event");
		for (int i = 0; i < eventCanvasEventNodes.getLength(); i++) {
			Element eventNode = (Element) eventCanvasEventNodes.item(i);	
		
			
			NodeList eventTNodes = eventNode.getElementsByTagName("t");
			for (int j = 0; j < eventTNodes.getLength(); j++) {
				Element tNode = (Element) eventTNodes.item(j);
				int t = Integer.parseInt(tNode.getAttribute("v"));
				int row = Integer.parseInt(tNode.getAttribute("r"));
				setCursorT(t);
				eventCanvas.setSelectedRow(row);
				NodeList timeSignatureNodes = tNode.getElementsByTagName("timeSignature");
				IntStream.range(0, timeSignatureNodes.getLength()).mapToObj(k->(Element) timeSignatureNodes.item(k))
				.map(TimeSignatureEvent::fromXMLElement).forEach(timeSignature -> {
					eventCanvas.setSelectedValue(timeSignature);
				});
				NodeList tempoNodes = tNode.getElementsByTagName("tempo");
				IntStream.range(0, tempoNodes.getLength()).mapToObj(k->(Element) tempoNodes.item(k))
				.map(TempoEvent::fromXMLElement).forEach(tempo-> {
					eventCanvas.setSelectedValue(tempo);
				});
				NodeList stickyNodes = tNode.getElementsByTagName("note");
				IntStream.range(0, stickyNodes.getLength()).mapToObj(k->(Element) stickyNodes.item(k))
				.map(StickyNote::fromXMLElement).forEach(stickyNote-> {
					eventCanvas.setSelectedValue(stickyNote);
				});
			}		
		}
		
		NodeList instrumentNodes = root.getElementsByTagName("instrumentCanvas");
		for (int i = 0; i < instrumentNodes.getLength(); i++) {
			Element iNode = (Element) instrumentNodes.item(i);
			
			for (KiteTabCanvas<?> canvas : allCanvases) {
				if (canvas == eventCanvas) {
					continue;
				}
				
				if (canvas.getName().contentEquals(iNode.getAttribute("name"))) {
					
					NodeList eventNodes = iNode.getElementsByTagName("event");
					for (int j = 0; j < eventNodes.getLength(); j++) {
						Element e = (Element) eventNodes.item(j);
						int r = Integer.parseInt(e.getAttribute("r"));
						int t = Integer.parseInt(e.getAttribute("t"));
						String v = e.getAttribute("v");
						canvas.setSelectedRow(r);
						setCursorT(t);
						for (char c : v.toCharArray()) {							
							canvas.handleCharInput(c);
						}	
					}
				}
			}
			
		}
		updateMeasureLinePositions();
		repaintCanvases();
		
	}

	
	void saveXML(File file) throws Exception {
		
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newDefaultInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		
		Document doc = db.newDocument();
		Element root = doc.createElement("tabProject");
		root.setAttribute("xmlns","tab");		
		root.setAttribute("cursorT",""+cursorT.get());
		root.setAttribute("repeatT",""+cursorT.get());
		root.setAttribute("stopT",""+stopT0.get());
		root.setAttribute("selectedRow",""+selectedCanvas.get().getSelectedRow());
		root.setAttribute("selectedCanvas",""+allCanvases.indexOf(selectedCanvas.get()));
		root.setAttribute("xmlns:xsi","http://www.w3.org/2001/XMLSchema-instance");
		root.setAttribute("xsi:schemaLocation","tab ../schemas/projectFiles.xsd");
		
		doc.appendChild(root);
		Element eventElement = doc.createElement("eventCanvas");
		root.appendChild(eventElement);
		eventCanvas.data.keySet().forEach(t -> {
			eventCanvas.data.get(t).forEach((r,controlEvent) -> {
				Element e = doc.createElement("event");
				eventElement.appendChild(e);
				e.setAttribute("t",""+t);
				e.setAttribute("r",""+r);
				switch (controlEvent.getType()) {
				case NIL:
					break;
				case STICKY_NOTE:
					e.appendChild(((StickyNote) controlEvent).toXMLElement(doc));
					break;
				case TEMPO:
					e.appendChild(((TempoEvent) controlEvent).toXMLElement(doc));
					break;
				case TIME_SIGNATURE:
					e.appendChild(((TimeSignatureEvent) controlEvent).toXMLElement(doc));
					break;
				default:
					break;
				
				}
			});
		});
		
		allCanvases.stream().filter(a->a!=eventCanvas).forEach(canvas -> {
			Element instrumentElement = doc.createElement("instrumentCanvas");
			root.appendChild(instrumentElement);
			instrumentElement.setAttribute("name", canvas.getName());
			canvas.data.keySet().forEach(t -> {
				canvas.data.get(t).forEach((r,inputEvent) -> {
					Element e = doc.createElement("event");
					instrumentElement.appendChild(e);
					e.setAttribute("t",""+t);
					e.setAttribute("r",""+r);
					if (canvas instanceof DrumTabCanvas) {
						e.setAttribute("v",((PercRowToken) inputEvent).getToken());
					} else {
						e.setAttribute("v",(String) inputEvent);	
					}
				});
			});
			 
		});
		
		TransformerFactory tf = TransformerFactory.newDefaultInstance();
		Transformer t  = tf.newTransformer();
		t.setOutputProperty(OutputKeys.INDENT,"yes");
		t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount","4");
		t.transform(new DOMSource(doc), new StreamResult(file));
		t.transform(new DOMSource(doc), new StreamResult(System.out));
		
	}
	AbstractAction rToA(Runnable r) {
		return new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {			
				r.run();
			}			
		};
	}	
	
}