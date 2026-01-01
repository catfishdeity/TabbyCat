package tabsequencer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dialog.ModalityType;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MouseInfo;
import java.awt.Point;
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
import java.util.stream.Collectors;
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
import javax.swing.DefaultComboBoxModel;
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

import tabsequencer.config.CanvasConfig;
import tabsequencer.config.CanvasesConfig;
import tabsequencer.config.DrumCanvasConfig;
import tabsequencer.config.PercToken;
import tabsequencer.config.ProjectFileData;
import tabsequencer.config.StringCanvasConfig;
import tabsequencer.events.ControlEvent;
import tabsequencer.events.ControlEventType;
import tabsequencer.events.ProgramChange;
import tabsequencer.events.StickyNote;
import tabsequencer.events.TempoEvent;
import tabsequencer.events.TimeSignatureDenominator;
import tabsequencer.events.TimeSignatureEvent;


public class TabbyCat {
	
	public static void main(String[] args) {
		TabbyCat.getInstance(); 
	}
	private static TabbyCat instance;
	
	static String loadProjectKey = "LOAD PROJECT";
	static String newProjectCardKey = "NEW PROJECT";
	private ProjectFileData projectData = null;
	private CanvasesConfig canvasesConfig = CanvasesConfig.getXMLInstance();
	private Font font = new Font("Monospaced",Font.BOLD,10);
	private FontMetrics fontMetrics = new Canvas().getFontMetrics(font);
	int infoPanelHeight = fontMetrics.getMaxAscent()+10;
	int cellWidth = fontMetrics.stringWidth("88")+3;
	int rowHeight = fontMetrics.getMaxAscent()+2;
	final int scrollTimeMargin = 4;	
	static final double MIDDLE_C = 220.0 * Math.pow(2d, 3.0/12.0);	
	
	FileFilter fileFilter = new FileNameExtensionFilter(".tab files (.tab)","tab");
	final File defaultProjectPath = new File("scores");
	
	final Map<Point,String> instrumentClipboard = new HashMap<>();	
	final Map<Point,ControlEvent> eventClipboard = new HashMap<>();
	
	final AtomicReference<File> activeFile = new AtomicReference<>(null);	
	final AtomicBoolean fileHasBeenModified = new AtomicBoolean(false);
	final AtomicBoolean isSelectionMode = new AtomicBoolean(false);
	final AtomicBoolean isPlaying = new AtomicBoolean(false);
	
	final AtomicInteger tempo = new AtomicInteger(120);
	final AtomicBoolean playbackDaemonIsStarted = new AtomicBoolean(false);
		 
	final TreeMap<Integer,Integer> cachedMeasurePositions = new TreeMap<>();
	final HashSet<Integer> cachedBeatMarkerPositions = new HashSet<>();
	
	final ScheduledExecutorService playbackDaemon = Executors.newSingleThreadScheduledExecutor();
	final ScheduledExecutorService midiDaemon = Executors.newSingleThreadScheduledExecutor();	
		
	final JFrame frame = new JFrame("TabbyCat");
	
	LeftClickablePanelButton playButton, stopButton;
	final PlayStatusPanel playStatusPanel = new PlayStatusPanel();
	final NavigationCanvas navigationBar = new NavigationCanvas();
	final EventCanvas eventCanvas = new EventCanvas();	
	File defaultSoundfontFile = null;		
	
	final Map<File,Soundbank> loadedSoundfontCache = new HashMap<>();

	private CardLayout cardLayout;
	private JPanel cardPanel;
	
	KeyStroke k_Up = KeyStroke.getKeyStroke("UP");
	KeyStroke k_Down = KeyStroke.getKeyStroke("DOWN");
	KeyStroke k_Enter = KeyStroke.getKeyStroke("ENTER");
	
	void playbackDaemonFunction() {
		//DO NOT CALL THIS ON MASTER THREAD
		while (true) {
			if (!isPlaying.get()) {
				continue;
			}
			else {
				int t = projectData.getPlaybackT().get();
				
				try {
					
					midiDaemon.execute(() -> {
						//allCanvases.forEach(a->a.handleEvents(t));	
					});
					
					SwingUtilities.invokeAndWait(() -> {						
						frame.repaint();							
					});
						
				} catch (Exception e) {					
					e.printStackTrace();
				}
				
				projectData.getPlaybackT().getAndUpdate(i->i+1==projectData.getRepeatT().get()?projectData.getPlaybackStartT().get():i+1);
				long bpm = tempo.get();				
				Duration sixteenth = Duration.ofMinutes(1).dividedBy(bpm).dividedBy(4);  
				playbackDaemon.schedule(()->playbackDaemonFunction(),
						sixteenth.toMillis(),TimeUnit.MILLISECONDS);
				
				return;
			}
		}
	}
	
	void repaintCanvases() {
		//TODO PUT THIS BACK
		/*
		allCanvases.forEach(a->a.repaint());
		*/			
	}
	
	
	void pasteSelectedNotes() {
		//TODO PUT TIHS BACK
		/*
		if (selectedCanvas.get()== eventCanvas) {
			int timeOffset = eventClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt();
			int rowOffset = eventClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();	
			eventClipboard.forEach((point,controlEvent) -> {
				int t = projectData.getCursorT().get()+point.y-timeOffset;
				int row = eventCanvas.getSelectedRow()+point.x-rowOffset;
				eventCanvas.setSelectedValue(row,t,controlEvent);
			});
			updateMeasureLinePositions();		
		} else if (selectedCanvas.get() instanceof InstrumentCanvas) {
			int timeOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt();
			int rowOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();
			instrumentClipboard.forEach((point,s) -> {
				int t = projectData.getCursorT().get()+point.y-timeOffset;
				int row = selectedCanvas.get().getSelectedRow()+point.x-rowOffset;
				((InstrumentCanvas) selectedCanvas.get()).setSelectedValue(row,t,s);
			});
			
		} 
		*/
		repaintCanvases();
	}
	
	void cutSelectedNotes() {
		
		instrumentClipboard.clear();		
		eventClipboard.clear();
		
		if (isSelectionMode.get()) {
			/*
			Stream.of(selectedCanvas.get().innerSelectionCells,selectedCanvas.get().outerSelectionCells)
			.flatMap(a->a.stream()).forEach(point -> {
				
				Optional<?> opt = selectedCanvas.get().getValueAt(point.y,point.x);				
				if (opt.isPresent()) {
					if (selectedCanvas.get() instanceof EventCanvas) {						
						eventClipboard.put(point, (ControlEvent) opt.get());						
					} else if (selectedCanvas.get() instanceof InstrumentCanvas) {
						instrumentClipboard.put(point, (String) opt.get());
					}
				}
				selectedCanvas.get().removeValueAt(point.y,point.x);
			});
			selectedCanvas.get().clearSelectionT0AndRow();
			*/		
		}
		 
		isSelectionMode.set(false);
		
		repaintCanvases();
	}
	
	void copySelectedNotes() {
		instrumentClipboard.clear();		
		eventClipboard.clear();

		
		if (isSelectionMode.get()) {
			//TODO put this back
			/*
			Stream.of(selectedCanvas.get().innerSelectionCells,selectedCanvas.get().outerSelectionCells)
			.flatMap(a->a.stream()).forEach(point -> {
				
				Optional<?> opt = selectedCanvas.get().getValueAt(point.y,point.x);				
				if (opt.isPresent()) {
					if (selectedCanvas.get() instanceof EventCanvas) {
						
						eventClipboard.put(point, (ControlEvent) opt.get());
					} else if (selectedCanvas.get() instanceof InstrumentCanvas) {
						instrumentClipboard.put(point, (String) opt.get());
					}
				}
			});
			selectedCanvas.get().clearSelectionT0AndRow();
			*/		
		} 
		isSelectionMode.set(false);
		repaintCanvases();
	}
	
	void addStaccato() {
		
	}
	
	void addAccent() {
		
	}
	
	void configureCanvasInstrument() {
		//TODO put this back
		/*
		if (selectedCanvas.get() instanceof TabCanvas) {
			((TabCanvas)selectedCanvas.get()).displayInstrumentDialog();
		} else if (selectedCanvas.get() instanceof DrumTabCanvas) {
			((DrumTabCanvas)selectedCanvas.get()).displayInstrumentDialog();
		}
		*/
	}
	
	void saveActiveFile() {
		if (!defaultProjectPath.exists()) {
			defaultProjectPath.mkdir();
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
			JFileChooser chooser = new JFileChooser(defaultProjectPath);
			chooser.setFileFilter(fileFilter);
			
			String date = LocalDateTime.now(ZoneId.of("Z")).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
			chooser.setSelectedFile(new File(date+".tab"));
			if (chooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
				try {
					File f = chooser.getSelectedFile();
					if (!f.getAbsolutePath().endsWith(".tab")) {
						f = new File(f.getAbsoluteFile()+".tab");
					}
					saveXML(f);
					activeFile.set(f);
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
		projectData.getRepeatT().updateAndGet(i->i==projectData.getCursorT().get()?-1:projectData.getCursorT().get());
		repaintCanvases();
	}
	
	void setPlayT() {
		projectData.getPlaybackT().set(projectData.getCursorT().get());
		repaintCanvases();
	}
	
	void togglePlayStatus() {
		if (isPlaying.get()) {
			stopPlayback();
		} else {
			startPlayback();
		}		
	}
	
	void returnCursorToStopT0() {
		projectData.getCursorT().set(projectData.getPlaybackStartT().get());
		while (projectData.getCursorT().get() < projectData.getViewT().get()+scrollTimeMargin) {
			projectData.getViewT().getAndDecrement();
		}
		repaintCanvases();
	}
	
	void returnPlayToStopT0() {
		projectData.getPlaybackT().set(projectData.getPlaybackStartT().get());
		while (projectData.getPlaybackT().get() < projectData.getViewT().get()+scrollTimeMargin) {
			projectData.getViewT().getAndDecrement();
		}
		repaintCanvases();
		
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
		
		//TODO put this back
		/*
		instrumentCanvases.forEach(soundfontPlayer -> {
			soundfontPlayer.silence();
		});

*/
		repaintCanvases();
		playStatusPanel.setPlayStatus(PlayStatus.STOP);
		playStatusPanel.repaint();	
	}
	
	void setStopT0() {
		projectData.getPlaybackStartT().set(projectData.getCursorT().get());
		repaintCanvases();
	}
		
	public int getCursorT() {
		return projectData.getCursorT().get();
	}
	
	public void setCursorT(int t) {
		projectData.getCursorT().set(t);
	}
	
	public void incrementCursorT() {		
		projectData.getCursorT().getAndIncrement();
		if (projectData.getCursorT().get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			projectData.getViewT().getAndIncrement();
		}
		repaintCanvases();
	}
	
	public void decrementCursorT() {
		projectData.getCursorT().set(Math.max(0, projectData.getCursorT().get()-1));
		if (projectData.getCursorT().get() < projectData.getViewT().get()+scrollTimeMargin) {
			projectData.getViewT().getAndDecrement();
		}
		repaintCanvases();
	}
	
	
	public void toggleSelectionMode() {
		
		isSelectionMode.set(!isSelectionMode.get());
		if (isSelectionMode.get()) {
			setSelectedT0();
		} else {
			//TODO put this back
			//allCanvases.forEach(canvas -> canvas.clearSelectionT0AndRow());
		}
		repaintCanvases();
	}
	
	void setSelectedT0() {
		//TODO fix me
		/*
		allCanvases.forEach(canvas -> {
			if (canvas.isSelected()) {
				canvas.setSelectionT0AndRow();
				
			} else {
				canvas.clearSelectionT0AndRow();
			}
			canvas.repaint();
		});
		*/
	}
	
	public void updateMeasureLinePositions() {		
		AtomicReference<TimeSignatureEvent> timeSignature = new AtomicReference<>(new TimeSignatureEvent(4,TimeSignatureDenominator._4));
		AtomicInteger counter = new AtomicInteger(0);
		AtomicInteger measure = new AtomicInteger(1);
		Map<Integer,Integer> measures = new TreeMap<>();
		Set<Integer> markers = new HashSet<>();
		measures.put(0,measure.getAndIncrement());
		for (int t = 0; t < 1000*16; t++) {
			int t_= t;
			Optional<TimeSignatureEvent> tsO = IntStream.range(0, eventCanvas.getRowCount()).mapToObj(row->new Point(t_,row))
			.filter(a->projectData.getEventData().containsKey(a))
			.map(projectData.getEventData()::get)
			.filter(a->a.getType()== ControlEventType.TIME_SIGNATURE)
			.map(a->(TimeSignatureEvent) a)
			.findFirst();

			if (tsO.isPresent()) {				
				timeSignature.set(tsO.get());				
				counter.set(0);
				measures.put(t,measure.get());
				if (t > 0) {
					measure.getAndIncrement();
					
				}
			} else {
				if (counter.get() == timeSignature.get().get16ths()) {
					counter.set(0);
					measures.put(t,measure.getAndIncrement());								
				}
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
		//TODO fix 
		/*
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
		*/
	}
	
	public void handleABCInput(char c) {
		projectData.handleCharInput(c);
		//selectedCanvas.get().handleCharInput(c);
		
	}
	
	public void cursorToStart() {
		projectData.getCursorT().set(0);
		while (projectData.getCursorT().get() < projectData.getViewT().get()) {
			projectData.getViewT().getAndDecrement();
		}
		repaintCanvases();
	}
	
	void updateDataLength() {
		//int maxT= allCanvases.stream().flatMapToInt(c->c.data.keySet().stream().mapToInt(i->i)).max().orElseGet(()->0)+16;
		int maxT = 10;
		
		navigationBar.dataLength = maxT;
		navigationBar.repaint();
		System.out.println(maxT);
		
	}
	
	public void advanceCursorToFinalEvent() {

		AtomicReference<GeneralTabCanvas<?>> canvasToSelect = new AtomicReference<>(eventCanvas);
		int maxT = Math.max(
				projectData.getEventData().keySet().stream().mapToInt(a->a.x).max().orElse(0),
				projectData.getInstrumentData().keySet().stream().mapToInt(a->a.getTime()).max().orElse(0));
		setCursorT(maxT);
		projectData.getEventData().keySet().stream().filter(a->a.x == maxT).findFirst().map(a->a.y).ifPresent(row -> {
			canvasToSelect.get().setSelectedRow(row);			
		});
		projectData.getInstrumentData().keySet().stream().filter(a->a.getTime() == maxT).findFirst().ifPresent(dataKey -> {
			//TODO put this back
			/*
			instrumentCanvases.stream().filter(a->a.getName().equals(dataKey.getInstrumentName())).findFirst()
			.ifPresent(canvas -> {
				canvasToSelect.set(canvas);
				canvas.setSelectedRow(dataKey.getRow());
			});
			*/
		});
				
		while (projectData.getCursorT().get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			projectData.getViewT().getAndIncrement();
		}
		
		//selectedCanvas.set(canvasToSelect.get());
		repaintCanvases();
		
	}
	
	public void playTToPreviousMeasure() {
		NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(projectData.getPlaybackT().get(), false);
		if (!headMap.isEmpty()) {
			Entry<Integer, Integer> last= headMap.lastEntry();			
			projectData.getPlaybackT().set(last.getKey());			
		}
		while (projectData.getPlaybackT().get() < projectData.getViewT().get()+scrollTimeMargin) {
			projectData.getViewT().getAndDecrement();
		}
		repaintCanvases();
	}
	
	public void playTToNextMeasure() {
		NavigableMap<Integer, Integer> tailMap = cachedMeasurePositions.tailMap(projectData.getPlaybackT().get(), false);
		if (!tailMap.isEmpty()) {
			Entry<Integer, Integer> next = tailMap.firstEntry();			
			projectData.getPlaybackT().set(next.getKey());			
		}
		while (projectData.getPlaybackT().get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			projectData.getViewT().getAndIncrement();
		}
		repaintCanvases();
	}
	
	public void incrementPlayT() {
		projectData.getPlaybackT().incrementAndGet();
		while (projectData.getPlaybackT().get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			projectData.getViewT().getAndIncrement();
		}
		repaintCanvases();
	}
	
	public void decrementPlayT() {
		projectData.getPlaybackT().getAndUpdate(i->Math.max(0,i-1));
		while (projectData.getPlaybackT().get() < projectData.getViewT().get()+scrollTimeMargin) {
			projectData.getViewT().getAndDecrement();
		}
		repaintCanvases();
	}
	
	public void shiftArrowUp() {
		if (isSelectionMode.get()) {
			return;
		}
		//TODO fix this
		/*
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
		*/
	}
	
	public void shiftArrowDown() {
		if (isSelectionMode.get()) {
			return;
		}
		//TODO fix this
		/*
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
		*/
		
	}
	
	public void arrowUp() {
		//TODO fix this
		/*
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
		*/
	}
	
	public void arrowDown() {
		//TODO fix this
		/*
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
		*/
	}
	
	public void cursorTToNextMeasure() {
		
		NavigableMap<Integer, Integer> tailMap = cachedMeasurePositions.tailMap(projectData.getCursorT().get(), false);
		if (!tailMap.isEmpty()) {
			Entry<Integer, Integer> next = tailMap.firstEntry();			
			projectData.getCursorT().set(next.getKey());			
		}
		while (projectData.getCursorT().get() > eventCanvas.getMaxVisibleTime()-scrollTimeMargin) {
			projectData.getViewT().getAndIncrement();
		}
		repaintCanvases();
		
	}
	
	public void cursorTToPrevMeasure() {
		NavigableMap<Integer, Integer> headMap = cachedMeasurePositions.headMap(projectData.getCursorT().get(), false);
		if (!headMap.isEmpty()) {
			Entry<Integer, Integer> last= headMap.lastEntry();			
			projectData.getCursorT().set(last.getKey());			
		}
		while (projectData.getCursorT().get() < projectData.getViewT().get()+scrollTimeMargin) {
			projectData.getViewT().getAndDecrement();
		}
		repaintCanvases();
	}
		
	
	public static TabbyCat getInstance() {
		if (instance == null) {
			instance = new TabbyCat();
		}
		return instance;
	}
	
	private TabbyCat() {		
		createGui();		
	}
	
	abstract class GeneralTabCanvas<A> extends JPanel {
		
		//protected final TreeMap<Integer,Map<Integer,A>> data = new TreeMap<>();
		AtomicInteger selectedRow = new AtomicInteger(0);		
		AtomicInteger selectionT0= new AtomicInteger(-1);
		AtomicInteger selectionRow0= new AtomicInteger(-1);
		public abstract int getRowCount();
		public abstract void handleEvents(int t);
		public abstract boolean handleCharInput(char c);
		public abstract String getName();			
		
		GeneralTabCanvas() {
			this.addMouseListener(new MouseAdapter() {
				@Override
				public void mouseClicked(MouseEvent me) {
					/*
					int t_ = (int) Math.floor(me.getPoint().getX()/cellWidth)+projectData.getViewT().get();										
					int row = (int) Math.floor((me.getPoint().y-infoPanelHeight)/rowHeight);
					if (selectedCanvas.get() != null) {						
						selectedCanvas.get().repaint();
					}
					selectedCanvas.set(GeneralTabCanvas.this);					
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
					*/
				}				
			});
		}
		
		
		public final void setSelectionT0AndRow() {		
			selectionT0.set(projectData.getCursorT().get());
			selectionRow0.set(getSelectedRow());			
		}
		
		public final void clearSelectionT0AndRow() {
			selectionT0.set(-1);
			selectionRow0.set(-1);
		}
		
		public final boolean isSelected() {
			return false;
			//TODO fix this!
			//return this == selectedCanvas.get(); 
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
		private Color selectionOuterColor = new Color(0,150,100);
		private Color selectionInnerColor = new Color(0,75,50);
		
		protected final Set<Point> outerSelectionCells = new HashSet<>();
		protected final Set<Point> innerSelectionCells = new HashSet<>();
		
		public final void drawGrid(Graphics2D g) {
			g.setFont(font);
			outerSelectionCells.clear();
			innerSelectionCells.clear();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setPaint(isSelected()?selectedHeaderBackgroundColor:unselectedHeaderBackgroundColor);			
			g.fillRect(0,0,getWidth(),getHeight());			  
			g.setStroke(new BasicStroke(1));			
			g.setPaint(isSelected()?selectedLabelTextColor:unselectedLabelTextColor);
			g.drawString(getName(),2,fontMetrics.getMaxAscent());			
			int t0 = projectData.getViewT().get();
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
				if (t == projectData.getPlaybackT().get()) {
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
			
			if (isSelected() && selectionT0.get() != -1 && selectionRow0.get() != -1) {
				//means we're selecting things				
				int sT0 = Math.min(projectData.getCursorT().get(), selectionT0.get());
				int sT1 = Math.max(projectData.getCursorT().get(), selectionT0.get());
				int row0 = Math.min(getSelectedRow(),selectionRow0.get());
				int row1 = Math.max(getSelectedRow(),selectionRow0.get());
				IntStream.rangeClosed(row0, row1).forEach(row -> {
					outerSelectionCells.add(new Point(row,sT0));
					outerSelectionCells.add(new Point(row,sT1));
				});
				IntStream.rangeClosed(sT0, sT1).forEach(t-> {
					outerSelectionCells.add(new Point(row0,t));
					outerSelectionCells.add(new Point(row1,t));
				});
				
				for (int row= 0; row < getRowCount(); row++) {
					for (int t = t0; t < t1; t++) {
						Point p = new Point(row,t);
						if ((row == row0 || row == row1) && (t >= sT0 && t <= sT1)) {
							outerSelectionCells.add(p);
						} else if ((t == sT0 || t == sT1) && (row >= row0 && row  <= row1)) {
							outerSelectionCells.add(p);
						} else if (row > row0 && row < row1 && t > sT0 && t < sT1) {
							innerSelectionCells.add(p);
						}
					}
				}				
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
					if (outerSelectionCells.contains(new Point(row,t))) {
						g.setPaint(selectionOuterColor);
						g.fillRect(x+1, y-rowHeight+1, cellWidth-2,rowHeight-2);
					}
					if (innerSelectionCells.contains(new Point(row,t))) {
						g.setPaint(selectionInnerColor);
						g.fillRect(x+1, y-rowHeight+1, cellWidth-2,rowHeight-2);
					}
					if (isSelected() && row == getSelectedRow() && 
							t == projectData.getCursorT().get()) {
						g.setPaint(selectedCellColor);
						g.setStroke(new BasicStroke(1));
						g.drawRect(x, y-rowHeight, cellWidth,rowHeight);
						
					}
					y+=rowHeight;
				}
				if (projectData.getPlaybackStartT().get() == t) {
					g.setStroke(new BasicStroke(1));
					g.setPaint(repeatColor);
					g.drawLine(x, infoPanelHeight, x, infoPanelHeight+getRowCount()*rowHeight);					
					g.drawLine(x+3, infoPanelHeight, x+3, infoPanelHeight+getRowCount()*rowHeight);
				}
				
				if (projectData.getRepeatT().get() == t && t>=0) {
					g.setStroke(new BasicStroke(1));
					g.setPaint(repeatColor);					
					g.drawLine(x, infoPanelHeight, x, infoPanelHeight+getRowCount()*rowHeight);					
					g.drawLine(x-3, infoPanelHeight, x-3, infoPanelHeight+getRowCount()*rowHeight);
				}
				x+=cellWidth;
			}
		}
		
		public final int getMaxVisibleTime() {
			int t0 = projectData.getViewT().get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			return t1;
		}
				
		//@SuppressWarnings("unchecked")
		public abstract Optional<A> getValueAt(int t, int row);
		/*
		{
			if (this == eventCanvas) {
				return (Optional<A>) Optional.ofNullable(eventData.get(new Point(t,row)));
			} else {
				return (Optional<A>) Optional.ofNullable(instrumentData.get(new InstrumentDataKey(this.getName(),t,row)));
			}			
		}
		*/
		public final Optional<A> getPrecedingValue() {
			return getValueAt(projectData.getCursorT().get()-1, getSelectedRow());					
		}

		public final Optional<A> getSelectedValue() {
			return getValueAt(projectData.getCursorT().get(), getSelectedRow());			
		}
		
		public abstract void setSelectedValue(int t, int row, A inputVal);
		
		public final void setSelectedValue(int t, A inputVal) {
			setSelectedValue(t,getSelectedRow(),inputVal);
		}
		
		public final void setSelectedValue(A inputVal) {			
			setSelectedValue(projectData.getCursorT().get(),inputVal);
			updateDataLength();
		}					
		
		public final Optional<A> removeSelectedValue() {
			return removeValueAt(projectData.getCursorT().get(),selectedRow.get());
			
		}
		
		public abstract Optional<A> removeValueAt(int t, int row);
		/*
			Optional<A> toReturn = Optional.ofNullable(data.getOrDefault(t, new HashMap<>()).get(row));
			data.getOrDefault(t,new HashMap<>()).remove(row);
			return toReturn;
		}
		*/
		
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
			double t0 = projectData.getViewT().get();
			
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
			double t0 = projectData.getViewT().get();				 				
			double displayedt1 = t0+navigationBar.getWidth()/cellWidth;
			projectData.getViewT().updateAndGet(a->Math.min(dataLength-(int)(displayedt1-t0), Math.max(0,a-deltaT)));			
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

	class EventCanvas extends GeneralTabCanvas<ControlEvent> {
				
		public EventCanvas() {}

		

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
			int t0 = projectData.getViewT().get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;			
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 					
				g.setFont(font);
				
				for (int rowNumber = 0; rowNumber < getRowCount(); rowNumber++) {					
					g.setPaint(Color.black);
					
					ControlEvent event = projectData.getEventData().get(new Point(t_,rowNumber));
					if (event != null) {
						switch (event.getType()) {
						
						case TIME_SIGNATURE:
							g.setFont(font.deriveFont(Font.ITALIC));
							g.setPaint(Color.YELLOW);
							g.drawString(event.toString(),x+1,y-3);
							g.setFont(font);
							break;
						case PROGRAM_CHANGE:
							g.setFont(font.deriveFont(Font.ITALIC));
							g.setPaint(new Color(100,200,255));
							g.drawString(event.toString(),x+1,y-3);
							g.setFont(font);
							break;
						case TEMPO:
							g.setFont(font.deriveFont(Font.ITALIC));
							g.setPaint(new Color(255,200,100));
							g.drawString(event.toString(),x+1,y-3);
							g.setFont(font);
							break;
						case STICKY_NOTE:
							g.setFont(font.deriveFont(Font.ITALIC));
							g.setPaint(Color.WHITE);
							g.drawString(((StickyNote) event).getText(),x+1,y-3);
						default:
							break;					
						}					
					}					
					y+=rowHeight;
				}				
				x+=cellWidth;
			}
			
			if (!eventClipboard.isEmpty()) {
				g.setPaint(Color.pink);
				int timeOffset = eventClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt();
				int rowOffset = eventClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();
				eventClipboard.forEach((point,controlEvent) -> {
					int t = projectData.getCursorT().get()+point.y-timeOffset;
					int row = getSelectedRow()+point.x-rowOffset;
					int x_ = (t-t0)*cellWidth;
					int y = rowHeight+infoPanelHeight+(rowHeight*row);
					if (row >= 0) {
						g.drawString(controlEvent.toString(),x_+1,y-3);
					}
				});
				
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
				//TODO put this back
				/*
				controlEvent.filter(a->a.getType()==ControlEventType.PROGRAM_CHANGE)
				.ifPresent(event -> {
					allCanvases.stream().filter(a->a.getName().equals(((ProgramChange) event).getInstrument()))
					.findFirst()
					.ifPresent(canvas -> {
						try {
							((TabCanvas) canvas).programChange((ProgramChange) event);
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					});
				});
				*/
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
			case 'P':
				addProgramChange();
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

		public Optional<TimeSignatureEvent> getTimeSignatureEventForTime(int t_) {
			return Optional.ofNullable(projectData.getEventData().get(new Point(t_,getSelectedRow())))
					.filter(a->a.getType() == ControlEventType.TIME_SIGNATURE)
					.map(a->(TimeSignatureEvent) a);
			
		}	
		
		@Override
		public Optional<ControlEvent> getValueAt(int t, int row) {
			return Optional.ofNullable(projectData.getEventData().get(new Point(t,row)));
		}

		@Override
		public void setSelectedValue(int t, int row, ControlEvent inputVal) {
			projectData.getEventData().put(new Point(t,row), inputVal);
			
		}

		@Override
		public Optional<ControlEvent> removeValueAt(int t, int row) {
			Optional<ControlEvent> toReturn = Optional.ofNullable(projectData.getEventData().get(new Point(t,row)));
			projectData.getEventData().remove(new Point(t,row));
			return toReturn;
			
		}
	}
	
	class TabCanvas extends InstrumentCanvas  {		
		
		private final String name;		
		

		private final Map<Integer,String> openNotes = new ConcurrentHashMap<>();
		private final Map<MidiChannel,Integer> openMidiNums = new ConcurrentHashMap<>();
		
		Synthesizer synth = null;
		//Map<Integer,Synthesizer> synths = new HashMap<>();
		private File soundfontFile = defaultSoundfontFile;
		private final AtomicReference<Instrument> loadedInstrument = new AtomicReference<>(null);
		private final StringCanvasConfig canvasConfig;
				
		public TabCanvas(StringCanvasConfig config) {
			this.canvasConfig = config;
			
			this.name = config.getName();			
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
						Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()).forEach(model::addElement);
							
						//model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
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
					Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()).forEach(model::addElement);
					//model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
					instrumentList.setModel(model);
				} catch (Exception ex) {
					ex.printStackTrace();
				}				
			} else {
				soundbankTextField.setText("<default>");
				try {
					DefaultListModel<Instrument> model = new DefaultListModel<>();
					Arrays.asList(synth.getLoadedInstruments()).forEach(model::addElement);
					//model.addAll(Arrays.asList(synth.getLoadedInstruments()));					
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
							Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()).forEach(model::addElement);
//							/model.addAll(Arrays.asList(MidiSystem.getSoundbank(soundfontFile).getInstruments()));					
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
		
		//public double[] getBaseFrequencies() {
			//return baseFrequencies;
		//}
				
		@Override
		public Dimension getSize() {				 			
			return new Dimension(super.getWidth(),infoPanelHeight + rowHeight * getRowCount());
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
			int t0 = projectData.getViewT().get();
			int tDelta = getWidth()/cellWidth;
			int t1 = t0+tDelta;
			int x = 0;
			for (int t_ = t0; t_<t1; t_+=1) {
				int y = rowHeight+infoPanelHeight; 
				g.setFont(font);
				for (int row = 0; row< getRowCount(); row++) {
					g.setPaint(Color.WHITE);
					Point p = new Point(row,t_);
					if (outerSelectionCells.contains(p) || innerSelectionCells.contains(p)) {
						g.setPaint(Color.RED);
					}
					String s = projectData.getInstrumentData().getOrDefault(getDataKey(t_,row), "");					
					g.drawString(s,(int) (x+(cellWidth-fontMetrics.stringWidth(s))*0.5),y-3);
					y+=rowHeight;
				}
				x+=cellWidth;
			}
			if (!instrumentClipboard.isEmpty() && isSelected()) {
				g.setPaint(Color.pink);
				int timeOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt();
				int rowOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();
				instrumentClipboard.forEach((point,guitarEvent) -> {
					int t = projectData.getCursorT().get()+point.y-timeOffset;
					int row = getSelectedRow()+point.x-rowOffset;
					int x_ = (t-t0)*cellWidth;
					int y = rowHeight+infoPanelHeight+(rowHeight*row);
					if (row >= 0) {
						g.drawString(guitarEvent.toString(),x_+1,y-3);
					}
				});
				
			}
		}

		@Override
		public int getRowCount() {			
			return canvasConfig.getEdoSteps().length;
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

				
				Optional<String> inputValO = this.getValueAt(t, row);
				if (inputValO.isPresent()) {
					String inputVal = inputValO.get();
					double baseFreq = canvasConfig.getBaseFrequency()*Math.pow(2.0, canvasConfig.getEdoSteps()[row]/canvasConfig.getEd2());

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
							double freq = baseFreq*Math.pow(2, canvasConfig.getFretStepSkip()*edoSteps/canvasConfig.getEd2());
							f.accept(row, freq);
						} else {
							
							double edoSteps = Double.parseDouble(inputVal);
							
							double freq = baseFreq*Math.pow(2.0, canvasConfig.getFretStepSkip()*edoSteps/canvasConfig.getEd2());
							System.out.println(getName()+" "+canvasConfig.getFretStepSkip()*edoSteps/canvasConfig.getEd2());
							System.out.println(canvasConfig.getFretStepSkip()+" "+edoSteps+" "+canvasConfig.getEd2());
							
							f.accept(row, freq);
						}					
					}
				} else  {
					noteOff(row);
				}
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
					int t = projectData.getCursorT().get()+1;
				
					while (!getValueAt(t,getSelectedRow()).filter(a->a.contentEquals("-")).isEmpty()) {
						//instrument
						projectData.getInstrumentData().remove(getDataKey(t,getSelectedRow()));
						t++;
						//projectData.getInstrumentData().getOrDefault(new t, Collections.emptyMap()).remove(getSelectedRow());
						//					t++;
					}
				}
				IntStream.iterate(projectData.getCursorT().get(),i->i-1).takeWhile(i->i>=0)
				.filter(t->
					projectData.getInstrumentData().get(getDataKey(t,getSelectedRow())) != null)
				.filter(t->projectData.getInstrumentData().get(getDataKey(t,getSelectedRow())).charAt(0) != '-')
				.max().ifPresent(previousEventT0 -> {
					for (int t = previousEventT0+1; t < projectData.getCursorT().get(); t+=1) {
						projectData.getInstrumentData().put(getDataKey(t,getSelectedRow()),"-");
					}
				});
				
								
				setSelectedValue(String.valueOf(c));
				if (!fileHasBeenModified.get()) {
					fileHasBeenModified.set(true);
					updateWindowTitle();
				}
				repaint();
				return true;
			}
			
			String token0 = getValueAt(projectData.getCursorT().get(),getSelectedRow()).orElse("");
			String token1 = token0+String.valueOf(c);
			
			Predicate<String> isValid = token -> {
				Matcher harmonicMatcher = harmonicPattern.matcher(token);
				if (harmonicMatcher.find() && (harmonicMatcher.group(1).trim().isEmpty() || 
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

		@Override
		public void programChange(ProgramChange event) {
			
			Arrays.asList(synth.getAvailableInstruments()).stream().filter(a->
			a.getPatch().getBank() == event.getBank()&& a.getPatch().getProgram() == event.getProgram())
			.findFirst().ifPresent(instrument -> {					
				loadedInstrument.set(instrument);					
				synth.loadInstrument(instrument);
				synth.getChannels()[9].programChange(event.getBank(),event.getProgram());
			
				for (int row = 0; row < getRowCount(); row++) {
					MidiChannel channel = synth.getChannels()[row<9?row:row+1];
					channel.programChange(event.getBank(), event.getProgram());
				}
			});
			
		}

		
	}
	
	abstract class InstrumentCanvas extends GeneralTabCanvas<String> {
		public abstract void initializeMidi(File file, int bank, int program);
		public void programChange(ProgramChange event) {
			
		}
		public void silence() {
			
		}
		public abstract Synthesizer getSynth();

		@Override
		public final Optional<String> getValueAt(int t, int row) {
			return Optional.ofNullable(projectData.getInstrumentData().get(getDataKey(t,row)));
		}
		
		public final InstrumentDataKey getDataKey(int t, int row) {
			return new InstrumentDataKey(this.getName(),t,row);
		}
		@Override
		public final void setSelectedValue(int t, int row, String value) {
			projectData.getInstrumentData().put(getDataKey(t,row),value);
		}
		
		@Override
		public final Optional<String> removeValueAt(int t, int row) {
			Optional<String> toReturn = Optional.ofNullable(projectData.getInstrumentData().get(getDataKey(t,row)));
			projectData.getInstrumentData().remove(getDataKey(t,row));
			return toReturn;
		}
		
	}
	
	class DrumTabCanvas extends InstrumentCanvas  {

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
			projectData.getInstrumentData().keySet().stream().filter(a->a.getTime() == t).map(projectData.getInstrumentData()::get).forEach(s -> {
				
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
						
			int t0 = projectData.getViewT().get();
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

					this.getValueAt(t_, row).ifPresent(s->{
						g.drawString(s,(int) (x_+(cellWidth-fontMetrics.stringWidth(s))*0.5),y_-3);	
					});

					y+=rowHeight;
				}				
				x+=cellWidth;
			}
			if (!instrumentClipboard.isEmpty()) {
				g.setPaint(Color.pink);
				int timeOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.y).min().getAsInt();
				int rowOffset = instrumentClipboard.keySet().stream().mapToInt(p->p.x).max().getAsInt();
				instrumentClipboard.forEach((point,drumEvent) -> {
					int t = projectData.getCursorT().get()+point.y-timeOffset;
					int row = getSelectedRow()+point.x-rowOffset;
					int x_ = (t-t0)*cellWidth;
					int y = rowHeight+infoPanelHeight+(rowHeight*row);
					if (row >= 0) {
						g.drawString(drumEvent,x_+1,y-3);
					}
				});
				
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
			
			Optional<String> tokenO = getValueAt(projectData.getCursorT().get(),getSelectedRow());			
			Predicate<PercToken> permitted = a->a.getPosition() == canvasConfig.getRowTypes().get(getSelectedRow());
			if (tokenO.isPresent()) {
				Optional<PercToken> newTokenO = 
						canvasConfig.getTokens().stream()
						.filter(a->a.getToken().equals(tokenO.get()+String.valueOf(c).toUpperCase()))
						.filter(permitted).findFirst();
				if (newTokenO.isPresent()) {					
					setSelectedValue(projectData.getCursorT().get(),newTokenO.get().getToken());
					repaint();
					return true;
				}				
			}	
			
			Optional<PercToken> newTokenO = 
					canvasConfig.getTokens().stream().filter(a->a.getToken().equals(String.valueOf(c).toUpperCase()))
					.filter(permitted).findFirst();
			
			if (newTokenO.isPresent()) {					
				setSelectedValue(projectData.getCursorT().get(),newTokenO.get().getToken());
				repaint();
				return true;
			}			
			return false;
		}

		@Override
		public void programChange(ProgramChange event) {

			Arrays.asList(synth.getAvailableInstruments()).stream().filter(a->
			a.getPatch().getBank() == event.getBank()&& a.getPatch().getProgram() == event.getProgram())
			.findFirst().ifPresent(instrument -> {					
				loadedInstrument.set(instrument);					
				synth.loadInstrument(instrument);
				synth.getChannels()[9].programChange(event.getBank(),event.getProgram());
			});
		}
	}
	
	/*
	void initializeCanvases() {
		try {
			CanvasesConfig config = CanvasesConfig.getXMLInstance();
			for (CanvasConfig canvasConfig : config.getCanvases()) {
				switch (canvasConfig.getType()) {
				case DRUM: {
					DrumCanvasConfig drumConfig = (DrumCanvasConfig) canvasConfig;					
					DrumTabCanvas canvas = new DrumTabCanvas(drumConfig);
					allCanvases.add(canvas);
					instrumentCanvases.add(canvas);
					break;
				}
				case STRING:{
					StringCanvasConfig stringConfig = (StringCanvasConfig) canvasConfig;
					TabCanvas canvas = new TabCanvas(stringConfig);
					allCanvases.add(canvas);
					instrumentCanvases.add(canvas);
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
	
	*/
	void createGui() {
		//initializeCanvases();
		/* 
		Box mainPanel = Box.createVerticalBox();
		InputMap inputMap = mainPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
		ActionMap actionMap = mainPanel.getActionMap();
		
		KeyStroke k_CtrlI = KeyStroke.getKeyStroke("control I");
		KeyStroke k_ctrlO = KeyStroke.getKeyStroke("control O");
		KeyStroke k_ctrlS = KeyStroke.getKeyStroke("control S");
		KeyStroke k_ctrlR = KeyStroke.getKeyStroke("control R");
		KeyStroke k_ctrlL = KeyStroke.getKeyStroke("control L");
		KeyStroke k_ctrlC = KeyStroke.getKeyStroke("control C");
		KeyStroke k_ctrlV = KeyStroke.getKeyStroke("control V");
		KeyStroke k_ctrlX = KeyStroke.getKeyStroke("control X");
		
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
		KeyStroke k_AltLeft = KeyStroke.getKeyStroke("alt LEFT");
		KeyStroke k_Right = KeyStroke.getKeyStroke("RIGHT");
		KeyStroke k_ShiftRight= KeyStroke.getKeyStroke("shift RIGHT");
		KeyStroke k_AltRight= KeyStroke.getKeyStroke("alt RIGHT");
		
		KeyStroke k_ShiftSpace = KeyStroke.getKeyStroke("shift SPACE");
		KeyStroke k_CtrlSpace = KeyStroke.getKeyStroke("ctrl SPACE");
		KeyStroke k_ShiftCtrlSpace = KeyStroke.getKeyStroke("shift ctrl SPACE");
		KeyStroke k_Space = KeyStroke.getKeyStroke("SPACE");
		KeyStroke k_Backspace = KeyStroke.getKeyStroke("BACK_SPACE");
		KeyStroke k_Delete= KeyStroke.getKeyStroke("DELETE");
		KeyStroke k_Period = KeyStroke.getKeyStroke("PERIOD");
		KeyStroke k_Comma= KeyStroke.getKeyStroke("COMMA");
		KeyStroke k_Hyphen= KeyStroke.getKeyStroke("MINUS");
		
		
		inputMap.put(k_ctrlC, "copySelectedNotes");
		actionMap.put("copySelectedNotes",rToA(this::copySelectedNotes));
		inputMap.put(k_ctrlV, "pasteSelectedNotes");
		actionMap.put("pasteSelectedNotes",rToA(this::pasteSelectedNotes));
		inputMap.put(k_ctrlX, "cutSelectedNotes");
		actionMap.put("cutSelectedNotes",rToA(this::cutSelectedNotes));
		inputMap.put(k_ctrlL, "toggleSelectionMode");
		actionMap.put("toggleSelectionMode",rToA(this::toggleSelectionMode));
		inputMap.put(k_CtrlSpace,"ctrlspace");
		actionMap.put("ctrlspace",rToA(this::returnPlayToStopT0));
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
		

		inputMap.put(k_Space, "togglePlayStatus");
		actionMap.put("togglePlayStatus", rToA(() -> this.togglePlayStatus()));
		inputMap.put(k_ShiftCtrlSpace, "setStopT0");
		actionMap.put("setStopT0", rToA(() -> this.setStopT0()));

		inputMap.put(k_Backspace, "backspace");
		actionMap.put("backspace",rToA(this::backspace));
		inputMap.put(k_Delete, "delete");
		actionMap.put("delete",rToA(this::backspace));
		
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
		
		inputMap.put(k_CtrlLeft, "ctrlLeft");
		actionMap.put("ctrlLeft", rToA(this::decrementPlayT));
		inputMap.put(k_CtrlRight, "ctrlRight");
		actionMap.put("ctrlRight", rToA(this::incrementPlayT));
		
		inputMap.put(k_ShiftRight, "shiftRight");;
		actionMap.put("shiftRight", rToA(this::cursorTToNextMeasure));
		inputMap.put(k_ShiftLeft, "shiftLeft");;
		actionMap.put("shiftLeft", rToA(this::cursorTToPrevMeasure));
		
		inputMap.put(k_CtrlShiftLeft, "ctrlShiftLeft");
		actionMap.put("ctrlShiftLeft", rToA(this::playTToPreviousMeasure));
		inputMap.put(k_CtrlShiftRight, "ctrlShiftRight");
		actionMap.put("ctrlShiftRight", rToA(this::playTToNextMeasure));
		
		
		inputMap.put(k_AltLeft, "AltLeft");
		actionMap.put("AltLeft", rToA(this::cursorToStart));
		inputMap.put(k_AltRight, "AltRight");
		actionMap.put("AltRight", rToA(this::advanceCursorToFinalEvent));
		
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
		
		//mainPanel.add(navigationBar);
		

				
		updateMeasureLinePositions();		

		
		JScrollPane scrollPane = new JScrollPane(mainPanel,JScrollPane.VERTICAL_SCROLLBAR_NEVER,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		frame.getContentPane().add(scrollPane,BorderLayout.CENTER);
		frame.getContentPane().add(controlBar,BorderLayout.SOUTH);
		*/
		cardLayout = new CardLayout();
		cardPanel = new JPanel(cardLayout);
		
		LoadProjectPanel loadProjectPanel = new LoadProjectPanel();
		cardPanel.add(loadProjectPanel,loadProjectKey);
		NewProjectPanel newProjectPanel = new NewProjectPanel();
		cardPanel.add(newProjectPanel,newProjectCardKey);
		frame.getContentPane().add(cardPanel,BorderLayout.CENTER);
		frame.pack();
		frame.setSize(new Dimension(
				(int) Math.min(1000, Toolkit.getDefaultToolkit().getScreenSize().getWidth()),
				(int) Math.min(500, Toolkit.getDefaultToolkit().getScreenSize().getHeight())));
				
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.setVisible(true);
	}
	
	
	void addProgramChange() {
		JDialog dialog = new JDialog(frame,String.format("Adding program change(t = %d)",projectData.getCursorT().get()));
		dialog.setModalityType(ModalityType.APPLICATION_MODAL);
		JLabel canvasLabel = new JLabel("Canvas");
		DefaultComboBoxModel<String> canvasModel = new DefaultComboBoxModel<>();
		Map<String,InstrumentCanvas> nameToPlayerMap = new HashMap<>();
		/*
		allCanvases.stream().filter(a->a!=eventCanvas).forEach(a-> {
			nameToPlayerMap.put(a.getName(),(InstrumentCanvas) a);
			canvasModel.addElement(a.getName());	
		});
		*/
		JComboBox<String> canvasComboBox = new JComboBox<>(canvasModel);
		
		JList<Instrument> instrumentList = new JList<>();
		
		Runnable instrumentComboBoxF = () -> {
			Instrument[] instruments = 
					nameToPlayerMap.get(canvasComboBox.getSelectedItem().toString()).getSynth().getAvailableInstruments();
			//Instrument[] instruments = ((SoundfontPlayer) nameToPlayerMap.get(canvasComboBox.getSelectedItem().toString()))
					//.getSynth().getAvailableInstruments();
			DefaultListModel<Instrument> model = new DefaultListModel<>();
			//model.addAll(Arrays.asList(instruments));
			Arrays.asList(instruments).forEach(model::addElement);
			instrumentList.setModel(model);
		};
		instrumentComboBoxF.run();
		canvasComboBox.addActionListener(ae -> instrumentComboBoxF.run());
		
		JScrollPane instrumentScrollPane = new JScrollPane(instrumentList,JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		
		Runnable r = () -> {
			if (instrumentList.getSelectedValue() == null) {
				return;
			}
			ProgramChange programChange = new ProgramChange(
					nameToPlayerMap.get(canvasComboBox.getSelectedItem()).getName(),
					instrumentList.getSelectedValue().getPatch().getBank(),
					instrumentList.getSelectedValue().getPatch().getProgram());
			eventCanvas.setSelectedValue(programChange);
			eventCanvas.repaint();
			dialog.dispose();
		};
		Arrays.asList(canvasComboBox,instrumentList).forEach(component -> {
			component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
			.put(KeyStroke.getKeyStroke("ENTER"),"enterPressed");
			component.getActionMap().put("enterPressed",rToA(r));
		});
		
		Box topPanel = Box.createHorizontalBox();
		topPanel.add(canvasLabel);
		topPanel.add(canvasComboBox);
		topPanel.add(Box.createHorizontalGlue());
		dialog.add(topPanel,BorderLayout.NORTH);
		dialog.add(instrumentScrollPane,BorderLayout.CENTER);
		dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
		dialog.setLocationRelativeTo(null);
		
		dialog.pack();
		dialog.setVisible(true);

		
	}
	void addTempo() {
		JDialog dialog = new JDialog(frame,String.format("Adding tempo (t = %d)",projectData.getCursorT().get()));
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
		//TODO fix this
		/*
		if (selectedCanvas.get() != eventCanvas) {
			return;
		}
		JDialog dialog = new JDialog(frame,String.format("Adding time signature (t = %d)",projectData.getCursorT().get()));
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
	*/
	}
	
	void addStickyNote() {
		JDialog dialog = new JDialog(frame,String.format("Adding sticky note (t = %d)",projectData.getCursorT().get()));
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
		//allCanvases.forEach(a->a.data.clear());
		projectData.getInstrumentData().clear();
		projectData.getEventData().clear();
		SchemaFactory schemaF = SchemaFactory.newInstance("http://www.w3.org/2001/XMLSchema");
		Schema schema = schemaF.newSchema(new File("schemas/projectFiles.xsd"));
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		dbf.setNamespaceAware(true);
		dbf.setSchema(schema);
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.parse(file);
		ProjectFileData projectFileData = ProjectFileData.fromXMLElement(doc.getDocumentElement());
		this.projectData = projectFileData;
		
				
		updateMeasureLinePositions();
		repaintCanvases();
		
	}

	
	void saveXML(File file) throws Exception {
		DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
		DocumentBuilder db = dbf.newDocumentBuilder();
		Document doc = db.newDocument();
		Element root = projectData.toXMLElement(doc);
		doc.appendChild(root);
		TransformerFactory tf = TransformerFactory.newInstance();
		Transformer t  = tf.newTransformer();
		t.setOutputProperty(OutputKeys.INDENT,"yes");
		t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount","4");
		t.transform(new DOMSource(doc), new StreamResult(file));
		t.transform(new DOMSource(doc), new StreamResult(System.out));
		/*
		
		
		Document doc = db.newDocument();
		Element root = doc.createElement("tabProject");
		root.setAttribute("xmlns","tab");		
		root.setAttribute("cursorT",""+projectData.getCursorT().get());
		root.setAttribute("repeatT",""+repeatT.get());
		root.setAttribute("stopT",""+projectData.getPlaybackStartT().get());
		root.setAttribute("selectedRow",""+selectedCanvas.get().getSelectedRow());
		root.setAttribute("selectedCanvas",""+allCanvases.indexOf(selectedCanvas.get()));
		root.setAttribute("xmlns:xsi","http://www.w3.org/2001/XMLSchema-instance");
		root.setAttribute("xsi:schemaLocation","tab ../schemas/projectFiles.xsd");
		
		doc.appendChild(root);
		Element eventElement = doc.createElement("eventTabData");
		root.appendChild(eventElement);
		projectData.getEventData().keySet().forEach(t -> {
			eventCanvas.data.get(t).forEach((r,controlEvent) -> {
				Element e = doc.createElement("event");
				eventElement.appendChild(e);
				e.setAttribute("t",""+t);
				e.setAttribute("r",""+r);
				e.appendChild(controlEvent.toXMLElement(doc));				
			});
		});
		
		allCanvases.stream().filter(a->a!=eventCanvas).forEach(canvas -> {
			Element instrumentElement = doc.createElement("instrumentTabData");
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
		
		
		
		*/
	}
	AbstractAction rToA(Runnable r) {
		return new AbstractAction() {
			@Override
			public void actionPerformed(ActionEvent e) {			
				r.run();
			}			
		};
	}	
	
	class NewProjectPanel extends JPanel {
		
		StringBuffer songName = new StringBuffer();
		StringBuffer artistName = new StringBuffer();
		int selectedIndex = 0;
		public Set<Integer> selectedIndices = new HashSet<>();
		final int modulo = 3;
		public NewProjectPanel() {
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			inputMap.put(k_Up,"up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));
			
		}
		
		void down() {
			selectedIndex++;
			//if (selectedIndex < 2) {
			if (selectedIndex+1==3+canvasesConfig.getCanvases().size()) {
				selectedIndex=0;
			} //else {
				 
			//}
			repaint();
		}
		
		void up() {
			selectedIndex--;
			if (selectedIndex==-1) {
				selectedIndex=2+canvasesConfig.getCanvases().size();
			}
			repaint();
		}
		
		void enter() {
			if (selectedIndex>=2 && selectedIndex != 2+canvasesConfig.getCanvases().size()) {
				//System.out.println(canvasesConfig.getCanvases().get(selectedIndex-2));
				if (selectedIndices.contains(selectedIndex-2)) {
					selectedIndices.remove(selectedIndex-2);
				} else {
					selectedIndices.add(selectedIndex-2);
				}
				repaint();
			}
		}
		

		
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			//Map<Object,Point> stringPositions = new HashMap<>();
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(font.deriveFont(14f));
			FontMetrics metrics = g.getFontMetrics();
			g.setPaint(Color.BLACK);
			g.fill(this.getBounds());
			int y = metrics.getMaxAscent();
			int rowHeight = metrics.getMaxAscent();
			String titleLabel = "Title:";
			String artistLabel = "Artist:";
			int textFieldX = Stream.of(titleLabel,artistLabel).mapToInt(a->(int) metrics.stringWidth(a)).max().getAsInt();
			int textFieldWidth = metrics.stringWidth("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
			g.setPaint(Color.WHITE);
			g.drawString(titleLabel,2,y);
			g.setPaint(selectedIndex==0?Color.gray:Color.DARK_GRAY);
			g.fillRect(textFieldX,y-rowHeight,textFieldWidth,rowHeight);
			g.setPaint(Color.WHITE);
			g.setClip(new Rectangle2D.Double(textFieldX,y-rowHeight,textFieldWidth,rowHeight));
			g.drawString(songName.toString(),textFieldX+textFieldWidth-metrics.stringWidth(songName.toString()),y);
			g.setClip(null);
			y+=rowHeight;
			g.setPaint(Color.WHITE);
			g.drawString(artistLabel,2,y);
			g.setPaint(selectedIndex==1?Color.gray:Color.DARK_GRAY);
			g.fillRect(textFieldX,y-rowHeight,textFieldWidth,rowHeight);
			g.setPaint(Color.WHITE);
			g.setClip(new Rectangle2D.Double(textFieldX,y-rowHeight,textFieldWidth,rowHeight));
			g.drawString(artistName.toString(),textFieldX+textFieldWidth-metrics.stringWidth(artistName.toString()),y);
			g.setClip(null);
			y+=rowHeight;
			int yForInstruments = y;
			int xForInstruments = 2;
			Map<Integer, List<Pair<Integer, CanvasConfig>>> groupedByModulo = 
					IntStream.range(0, canvasesConfig.getCanvases().size()).mapToObj(i->new Pair<>(i,canvasesConfig.getCanvases().get(i)))
					.collect(Collectors.groupingBy(a->a.a%modulo));
			for (int i : groupedByModulo.keySet()) {
				int w = groupedByModulo.get(i).stream().mapToInt(a->metrics.stringWidth(a.b.getName())).max().orElse(0)+rowHeight+20;
				for (Pair<Integer,CanvasConfig> p : groupedByModulo.get(i)) {
					g.setPaint(Color.white);
					g.drawRect(xForInstruments, yForInstruments-rowHeight,rowHeight, rowHeight);
					g.setPaint(p.a.intValue() == selectedIndex-2?Color.DARK_GRAY:Color.black);
					g.fillRect(xForInstruments+rowHeight,yForInstruments-rowHeight,w-rowHeight,rowHeight);
					g.setPaint(Color.WHITE);
					if (selectedIndices.contains(p.a)) {
						g.drawString("\u2713",xForInstruments,yForInstruments);
					}
					g.drawString(p.b.getName(), rowHeight+2+xForInstruments,yForInstruments);
					yForInstruments+=rowHeight;
				}
				xForInstruments+=w;
				yForInstruments = y;
			}
			y+=rowHeight*groupedByModulo.values().stream().mapToInt(a->a.size()).max().orElse(1);
			
			g.setPaint(selectedIndex == canvasesConfig.getCanvases().size()+2?Color.DARK_GRAY:Color.black);
			g.fillRect(0,y-rowHeight,metrics.stringWidth("LOAD"),rowHeight);
			g.setPaint(selectedIndex == canvasesConfig.getCanvases().size()+2?new Color(255,255,150):Color.WHITE);
			g.drawString("LOAD", 2,y);
			
			
		}
	}
	class LoadProjectPanel extends JPanel {
		private File workingDir = defaultProjectPath;
		private int selectedIndex = 0;
		public LoadProjectPanel() {
			this.workingDir = defaultProjectPath;
			if (!workingDir.exists()) {
				workingDir.mkdir();
			}
			InputMap inputMap = this.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
			ActionMap actionMap = this.getActionMap();
			
			//KeyStroke k_Esc = KeyStroke.getKeyStroke("ESCAPE");
			inputMap.put(k_Up,"up");
			actionMap.put("up", rToA(this::up));
			inputMap.put(k_Down,"down");
			actionMap.put("down", rToA(this::down));
			inputMap.put(k_Enter,"enter");
			actionMap.put("enter", rToA(this::enter));
			
		}
		
		private void up() {
			int numFiles = 0;
			for (File f : workingDir.listFiles()) {
				if (f.isDirectory() || fileFilter.accept(f)) {
					numFiles++;
				}
			}
			
			if (selectedIndex==0) {
				selectedIndex = 1+numFiles;				
			} else {
				selectedIndex-=1;
			}
			repaint();
		}
		
		private void down() {
			int numFiles = 0;
			for (File f : workingDir.listFiles()) {
				if (f.isDirectory() || fileFilter.accept(f)) {
					numFiles++;
				}
			}
			selectedIndex+=1;
			if (selectedIndex == 2+numFiles) {
				selectedIndex = 0;
			}
			repaint();
			
		}
		
		private void enter() {
			if (selectedIndex == 0) {
				cardLayout.show(cardPanel, newProjectCardKey);
			} else if (selectedIndex == 1) {
				this.workingDir = new File(workingDir.getAbsolutePath()).getParentFile();
				repaint();
			} else {
				List<File> files = 
						Arrays.asList(workingDir.listFiles()).stream().filter(a->a.isDirectory() || fileFilter.accept(a))
						.toList();
				if (files.get(selectedIndex-2).isDirectory()) {
					workingDir = new File(workingDir.getAbsolutePath()+"/"+files.get(selectedIndex-2).getName());
					selectedIndex= 1;
					repaint();
				} else {
					System.out.println("loading "+files.get(selectedIndex-2));
				}
					
			}
		}
		

		
		@Override
		public void paint(Graphics g_) {
			Graphics2D g = (Graphics2D) g_;
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
			g.setFont(font.deriveFont(14f));
			FontMetrics metrics = g.getFontMetrics();
			g.setPaint(Color.BLACK);
			g.fill(this.getBounds());
			int y = metrics.getMaxAscent();
			g.setPaint(Color.RED);
			g.drawString(workingDir.getAbsolutePath(),getWidth()-metrics.stringWidth(workingDir.getAbsolutePath())-2, y);

			List<Pair<String,Color>> strings= new ArrayList<>();
			strings.add(new Pair<>("<New Project>",new Color(180,180,255)));
			strings.add(new Pair<>("..",new Color(255,255,180)));
			if (workingDir == null) {
				return;
			}
			for (File f : this.workingDir.listFiles()) {
				if (f.isDirectory() || fileFilter.accept(f)) {
					strings.add(new Pair<>(f.getName(),f.isDirectory()?new Color(255,255,180):new Color(180,255,180)));
				}
			
			}
			int w = strings.stream().mapToInt(a->metrics.stringWidth(a.a)).max().getAsInt();
			for (int i = 0; i < strings.size(); i++) {
				Pair<String,Color> p = strings.get(i);
				g.setPaint(selectedIndex == i?Color.DARK_GRAY:Color.black);
				g.fillRect(0, y-metrics.getMaxAscent(), w, metrics.getMaxAscent());
				g.setPaint(p.b);
				g.drawString(p.a, 2, y);
				y+=metrics.getMaxAscent();
			}
			
						
		}
	}

}