import java.awt.Canvas;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import javax.swing.JFrame;

public class MOS {
	public static void main(String[] args) {
		BigDecimal generator = new BigDecimal(356.381);
		BigDecimal period = new BigDecimal(1197.176);
		//BigDecimal generator = new BigDecimal("702.000");
		//BigDecimal period = new BigDecimal("1200.000");
		int w = 1000;
		int h= 1000;
		//BufferedImage img = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
		//Graphics2D g = img.createGraphics();		
		//g.fillRect(0, 0, w, h);
		//g.dispose();
		JFrame frame= new JFrame();
		
		Canvas canvas = new Canvas() {
			@Override
			public void paint(Graphics g_) {
				Graphics2D g = (Graphics2D) g_;
				int w = this.getWidth();
				int h = this.getHeight();
				g.setColor(Color.WHITE);
				g.fillRect(0, 0, w, h);
				g.setColor(Color.BLACK);
				g.draw(new Ellipse2D.Double(0,0, w, h));
			}};
		List<BigDecimal> pitches = new ArrayList<>();
		
		
		Supplier<Iterator<BigDecimal>> pitchGeneratorGenerator = () -> {
			return new Iterator<BigDecimal>() {
				BigDecimal pitch =  new BigDecimal(0);
				@Override
				public boolean hasNext() {				
					return true;
				}
				@Override
				public BigDecimal next() {
					BigDecimal toReturn = pitch;
					pitch = pitch.add(generator).setScale(3,RoundingMode.HALF_UP);
					if (pitch.compareTo(period) != -1) {
						pitch = pitch.subtract(period);
					}
					return toReturn;				
				}			
			};
		};
		IntFunction<List<BigDecimal>> genScale = i -> {
			Iterator<BigDecimal> pitchGenerator = pitchGeneratorGenerator.get();
			List<BigDecimal> toReturn = new ArrayList<>();
			toReturn.add(new BigDecimal("0.000"));
			pitchGenerator.next();
			for (int a = 0 ; a < i; a++) {
				toReturn.add(pitchGenerator.next());
			}
			toReturn.add(period);
			return toReturn.stream().sorted()
					.map(a->a.setScale(3,RoundingMode.HALF_UP))
					.collect(Collectors.toList());			
		};
		
		Function<List<BigDecimal>,Map<BigDecimal,Integer>> calcSteps = scale-> {
			Map<BigDecimal,Integer> m = new HashMap<>();
			 IntStream.range(0,scale.size()-1)
					.mapToObj(i->scale.get(i+1).subtract(scale.get(i)))
					.forEach(a-> {
						if (m.containsKey(a)) 
							m.put(a,m.get(a)+1);
						else
							m.put(a, 1);
					});
			 return m;
		};
		
		for (int i = 1; i <= 25; i++) {
			List<BigDecimal> scale = genScale.apply(i);
			Map<BigDecimal,Integer> m = calcSteps.apply(scale);

			if (m.size() == 2) {
				Deque<String> dq = Stream.of("S","L")
						.collect(Collectors.toCollection(ArrayDeque<String>::new));
				Map<BigDecimal,String> bg2s = m.keySet().stream().sorted().collect(
						Collectors.toMap(a->a,a->dq.poll()));
				BigDecimal large = bg2s.keySet().stream().sorted().skip(1).findFirst().get();
				BigDecimal small = bg2s.keySet().stream().sorted().skip(0).findFirst().get();				
				System.out.printf("%d : %dL%ds : L %s, S %s\n %s\n",i+1,
						m.get(large),m.get(small),
						large,small,
						IntStream.range(0, scale.size()-1).mapToObj(j->scale.get(j+1).subtract(scale.get(j)))
						.map(bg2s::get).map(a->a.toString()).reduce("",(a,b)->a+b));
				
				Iterator<BigDecimal> iter = pitchGeneratorGenerator.get();
				System.out.println(IntStream.range(0, i+1)
						.mapToObj(aoeu->iter.next().setScale(3,RoundingMode.HALF_EVEN))
						.map(a->a.toString()).reduce("",(a,b)->a+"\n"+b));
				
				//for (int a = 0; a < $i+1; a==)
						//IntStream.range(0, scale.size()).mapToObj(j->scale.get(j+1).subtract(scale.get(j)))
						//.map(bg2s::get).map(a->a.toString()).reduce("",(a,b)->a+b),
				
				//System.out.println(bg2s);
				//System.out.printf("%d %s\n",i+1,m);
				//scale.forEach(System.out::println);
			}
			//calcSteps.apply(genScale.apply(8)).forEach(System.out::println);
		}
		//genScale.apply(6).forEach(System.out::println);
		//frame.setSize(new Dimension(600,600));
		//frame.getContentPane().add(canvas,BorderLayout.CENTER);		
		//frame.setVisible(true);

	}
}
