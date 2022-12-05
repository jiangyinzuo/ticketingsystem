package ticketingsystem;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.HashSet;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

class ConfigReader {
	static int routenum = 3; // route is designed from 1 to 3
	static int coachnum = 3; // coach is arranged from 1 to 5
	static int seatnum = 5; // seat is allocated from 1 to 20
	static int stationnum = 5; // station is designed from 1 to 5

	static int refRatio = 10;
	static int buyRatio = 20;
	static int inqRatio = 30;

	private ConfigReader() {
	}

	static boolean readConfig(String filename) {
		try {
			Scanner scanner = new Scanner(new File(filename));

			while (scanner.hasNextLine()) {
				String line = scanner.nextLine();
				// System.out.println(line);
				Scanner linescanner = new Scanner(line);
				if (line.equals("")) {
					linescanner.close();
					continue;
				}
				if (line.substring(0, 1).equals("#")) {
					linescanner.close();
					continue;
				}
				routenum = linescanner.nextInt();
				coachnum = linescanner.nextInt();
				seatnum = linescanner.nextInt();
				stationnum = linescanner.nextInt();

				refRatio = linescanner.nextInt();
				buyRatio = linescanner.nextInt();
				inqRatio = linescanner.nextInt();
				System.out.println("route: " + routenum + ", coach: " + coachnum + ", seatnum: " + seatnum + ", station: "
						+ stationnum + ", refundRatio: " +
						refRatio + ", buyRatio: " + buyRatio + ", inquiryRatio: " + inqRatio);
				linescanner.close();
			}
			scanner.close();
		} catch (FileNotFoundException e) {
			System.out.println(e);
		}
		return true;
	}

}

class Latency {
	static int threadnum = 1;
	long latenciesSum = 0;
	long maxLatency = 0;
	long minLatency = Long.MAX_VALUE;
	int count = 0;

	public void report(long latency) {
		latenciesSum += latency;
		maxLatency = Long.max(maxLatency, latency);
		minLatency = Long.min(minLatency, latency);
		++count;
	}

	Latency reduce(Latency other) {
		latenciesSum += other.latenciesSum;
		maxLatency = Long.max(maxLatency, other.maxLatency);
		minLatency = Long.min(minLatency, other.minLatency);
		count += other.count;
		return this;
	}

	@Override
	public String toString() {
		return "count: " + count
				+ ", avg latency: " + laplaceSmooth(latenciesSum / 1000000, count) + " ms"
				+ ", max latency: " + maxLatency
				+ ", min latency: " + minLatency
				+ ", QPS: " + laplaceSmooth(count, latenciesSum / 1000000) * threadnum + " ops/ms";
	}

	private double laplaceSmooth(double a, double b) {
		return b == 0 ? a + 1 : a / b;
	}
}

class Metrics {
	Latency refundLatency = new Latency();
	Latency buyLatency = new Latency();
	Latency inquiryLatency = new Latency();
	long noTicketCounter = 0;

	Metrics reduce(Metrics other) {
		refundLatency.reduce(other.refundLatency);
		buyLatency.reduce(other.buyLatency);
		inquiryLatency.reduce(other.inquiryLatency);
		noTicketCounter += other.noTicketCounter;
		return this;
	}

	@Override
	public String toString() {
		Latency total = new Latency();
		total.reduce(refundLatency).reduce(buyLatency).reduce(inquiryLatency);
		return "[[total]]\n" + total
				+ "\n[refund]\n" + refundLatency
				+ "\n[buy]\n" + buyLatency
				+ "\n[inquiry]\n" + inquiryLatency + "\n" + "no ticket count: " + noTicketCounter + "\n";
	}
}

public class Test {

	public static void main(String[] args) throws InterruptedException {
		if (args.length != 2) {
			System.out.println("args: <threadnum> <testnum>");
			return;
		}
		Latency.threadnum = Integer.parseInt(args[0]);
		final int testnum = Integer.parseInt(args[1]);
		System.out.println("threadnum: " + Latency.threadnum + ", testnum: " + testnum);
		ConfigReader.readConfig("TrainConfig");

		final TicketingDS ds = new TicketingDS(ConfigReader.routenum, ConfigReader.coachnum, ConfigReader.seatnum,
				ConfigReader.stationnum, Latency.threadnum);

		Thread[] threads = new Thread[Latency.threadnum];
		Metrics[] metrics = new Metrics[Latency.threadnum];
		CyclicBarrier barrier = new CyclicBarrier(Latency.threadnum);

		AtomicLong st = new AtomicLong(0);
		for (int i = 0; i < Latency.threadnum; ++i) {
			metrics[i] = new Metrics();
			Metrics metrics2 = metrics[i];
			threads[i] = new Thread(() -> {
				final Random rand = new Random(Thread.currentThread().getId() * 1000000007L + System.currentTimeMillis());
				final HashSet<Ticket> tickets = new HashSet<>(testnum * ConfigReader.buyRatio);
				try {
					st.set(System.nanoTime());
					barrier.await();
				} catch (InterruptedException | BrokenBarrierException e) {
					e.printStackTrace();
				}
				for (int op = 0; op < testnum; ++op) {
					int randvalue = rand.nextInt(100);
					if (randvalue < ConfigReader.refRatio && !tickets.isEmpty()) {
						// refund
						Ticket ticket = tickets.iterator().next();
						assert ticket != null;
						tickets.remove(ticket);
						final long startTime = System.nanoTime();
						boolean result = ds.refundTicket(ticket);
						metrics2.refundLatency.report(System.nanoTime() - startTime);
						if (!result) {
							System.out.println("[ERROR] refund returns false. ticket: " + ticket);
							return;
						}
					} else if (randvalue < ConfigReader.refRatio + ConfigReader.buyRatio) {
						// buy
						int passengerID = rand.nextInt(1000000);
						String passenger = "p" + passengerID;

						int route = rand.nextInt(ConfigReader.routenum) + 1;
						int departure = rand.nextInt(ConfigReader.stationnum - 1) + 1;
						// arrival is always greater than departure
						int arrival = departure + rand.nextInt(ConfigReader.stationnum - departure) + 1;
						final long startTime = System.nanoTime();
						Ticket ticket = ds.buyTicket(passenger, route, departure, arrival);
						metrics2.buyLatency.report(System.nanoTime() - startTime);
						if (ticket != null) {
							tickets.add(ticket);
						} else {
							++metrics2.noTicketCounter;
						}
					} else {
						// inquiry
						int route = rand.nextInt(ConfigReader.routenum) + 1;
						int departure = rand.nextInt(ConfigReader.stationnum - 1) + 1;
						// arrival is always greater than departure
						int arrival = departure + rand.nextInt(ConfigReader.stationnum - departure) + 1;
						final long startTime = System.nanoTime();
						ds.inquiry(route, departure, arrival);
						metrics2.inquiryLatency.report(System.nanoTime() - startTime);
					}
				}
				System.out.println("t" + Thread.currentThread().getId() + " done");
			});
			threads[i].start();
		}
		for (Thread t : threads) {
			t.join();
		}
		long endtime = System.nanoTime();
		Metrics reduced = Stream.of(metrics).reduce(new Metrics(), Metrics::reduce);
		System.out.println(reduced);
		System.out.println("time: " + (endtime - st.get()) / 1000_000_000.0 + "s");
		System.out.println("client QPS: " + Latency.threadnum * testnum / ((endtime - st.get()) / 1000_000.0) + " ops/ms");
	}
}
