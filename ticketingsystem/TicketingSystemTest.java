package ticketingsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.locks.StampedLock;

import org.junit.jupiter.api.Test;

import ticketingsystem.Replay.HistoryLine;

class TicketingSystemTest {

	final int ROUTENUM = 50;
	final int COACHNUM = 20;
	final int SEATNUM = 100;
	final int STATIONNUM = 30;
	final int THREADNUM = 64;

	static class Config {
		int routenum;
		int coachnum;
		int seatnum;
		int stationnum;

		Config(int routenum, int coachnum, int seatnum, int stationnum) {
			this.routenum = routenum;
			this.coachnum = coachnum;
			this.seatnum = seatnum;
			this.stationnum = stationnum;
		}
	}

	@Test
	void buyTicket() {
		final TicketingDS ds = new TicketingDS(ROUTENUM, COACHNUM, SEATNUM, STATIONNUM, THREADNUM);
		assertNull(ds.buyTicket("foo", 1, 0, 0));
		assertNull(ds.buyTicket("foo", 1, 0, 8));
		assertNull(ds.buyTicket("foo", 1, 18, 8));
		assertNull(ds.buyTicket("foob", 0, 18, 8));

		for (int route = 1; route <= ROUTENUM; ++route) {
			for (int i = 0; i < COACHNUM * SEATNUM; ++i) {
				Ticket t = ds.buyTicket("alice", route, 1, STATIONNUM);
				assertNotNull(t);
			}
		}

		for (int route = 1; route <= ROUTENUM; ++route) {
			for (int departure = 1; departure < STATIONNUM; ++departure) {
				for (int arrival = departure + 1; arrival <= STATIONNUM; ++arrival) {
					Ticket t = ds.buyTicket("alice", route, 1, 2);
					int remain1 = ds.inquiry(route, departure, arrival);
					int remain2 = ds.inquiry(route, departure, arrival);
					assertNull(t);
					assertEquals(remain1, remain2);
				}
			}
		}
	}

	@Test
	void refundTicket() {
		final TicketingDS ds = new TicketingDS(ROUTENUM, COACHNUM, SEATNUM, STATIONNUM, THREADNUM);
		assertFalse(ds.refundTicket(null));
		int remain = ds.inquiry(4, 1, 6);
		assertEquals(COACHNUM * SEATNUM, remain);
		Ticket t = ds.buyTicket("bob", 4, 1, 6);
		assertNotNull(t);
		remain = ds.inquiry(4, 1, 6);
		assertEquals(COACHNUM * SEATNUM - 1, remain);
		assertTrue(ds.refundTicket(t));
		remain = ds.inquiry(4, 1, 6);
		assertEquals(COACHNUM * SEATNUM, remain);
	}

	@Test
	void inquiryTest1() {
		Config cfg = new Config(3, 3, 5, 5);
		final TicketingDS ds = new TicketingDS(cfg.routenum, cfg.coachnum, cfg.seatnum, cfg.stationnum, THREADNUM);
		Ticket t1 = ds.buyTicket("a", 3, 3, 4);
		Ticket t2 = ds.buyTicket("b", 3, 4, 5);
		assertTrue(ds.refundTicket(t2));
		int remain = ds.inquiry(3, 3, 4);
		assertEquals(cfg.coachnum * cfg.seatnum - 1, remain);
	}

	@Test
	void inquiryTest2() {
		Config cfg = new Config(3, 3, 5, 5);
		final TicketingDS ds = new TicketingDS(cfg.routenum, cfg.coachnum, cfg.seatnum, cfg.stationnum, THREADNUM);
		Ticket t2 = ds.buyTicket("b", 3, 4, 5);
		assertTrue(ds.refundTicket(t2));
		int remain = ds.inquiry(3, 1, 2);
		assertEquals(cfg.coachnum * cfg.seatnum, remain);
		remain = ds.inquiry(3, 1, 5);
		assertEquals(cfg.coachnum * cfg.seatnum, remain);
	}

	@Test
	void historyTest() {
		File dir = new File("");
		try {
			System.out.println(dir.getCanonicalPath());
		} catch (IOException e) {
			e.printStackTrace();
		}
		ArrayList<HistoryLine> historys = new ArrayList<>();
		Replay.readHistory(historys, "/pub/home/user042/myproject/ticketingsystem/testfile/history1");
		assertTrue(!historys.isEmpty());
		Config cfg = new Config(3, 3, 5, 5);
		HashMap<Integer, Integer> inquiry = caculateInquiryResult(cfg, historys);
		replayHistoryLine(cfg, historys, inquiry);
	}

	private HashMap<Integer, Integer> caculateInquiryResult(
			Config cfg,
			ArrayList<HistoryLine> historyLines) {
		HashMap<Integer, Integer> result = new HashMap<>();
		boolean[][][] stations = new boolean[cfg.coachnum + 1][cfg.seatnum + 1][cfg.stationnum + 1];
		int line = 0;
		for (HistoryLine historyLine : historyLines) {
			switch (historyLine.operationName) {
				case "buyTicket":
					for (int station = historyLine.departure + 1; station <= historyLine.arrival; ++station) {
						stations[historyLine.coach][historyLine.seat][station] = true;
					}
					break;
				case "refundTicket":
					for (int station = historyLine.departure + 1; station <= historyLine.arrival; ++station) {
						stations[historyLine.coach][historyLine.seat][station] = false;
					}
					break;
				case "inquiry":
					int sum = 0;
					for (int coach = 1; coach <= cfg.coachnum; ++coach) {
						for (int seat = 1; seat <= cfg.seatnum; ++seat) {
							boolean exclude = false;
							for (int station = historyLine.departure + 1; station <= historyLine.arrival; ++station) {
								exclude |= stations[coach][seat][station];
								if (exclude) {
									break;
								}
							}
							if (!exclude) {
								++sum;
							}
						}
					}
					result.put(line, sum);
					break;
				default:
					throw new RuntimeException("invalid method");
			}
			++line;
		}
		return result;
	}

	private void replayHistoryLine(
			Config cfg,
			ArrayList<HistoryLine> historyLines,
			HashMap<Integer, Integer> inquiry) {
		final TicketingDS ds = new TicketingDS(cfg.routenum, cfg.coachnum, cfg.seatnum, cfg.stationnum, THREADNUM);
		HashMap<RouteCoachSeat, Ticket> map = new HashMap<>();
		int line = 0;
		for (HistoryLine historyLine : historyLines) {
			executeHistory(historyLine.operationName, ds, map, historyLine.passenger, historyLine.route,
					historyLine.departure,
					historyLine.arrival,
					historyLine.coach, historyLine.operationName.equals("inquiry") ? inquiry.get(line) : historyLine.seat,
					Boolean.parseBoolean(historyLine.res));
			++line;
		}
	}

	@Test
	void inquiryTest4() {
		final String[] historys = { "10401109 11129682 0 buyTicket 42 passenger307 3 1 4 5 3 true " +
				"11494312 11566996 0 buyTicket 170 passenger921 3 3 2 5 1 true " +
				"11659313 11704271 0 buyTicket 298 passenger356 3 2 1 5 4 true " +
				"11794669 11883528 0 buyTicket 426 passenger689 1 1 1 5 3 true " +
				"11978816 12224552 0 inquiry 0 passenger677 1 0 2 4 14 true " +
				"12329866 12352723 0 inquiry 0 passenger680 1 0 3 5 14 true " +
				"12445953 12466437 0 inquiry 0 passenger387 3 0 1 4 13 true " +
				"12567749 12593723 0 inquiry 0 passenger770 2 0 3 4 15 true " +
				"12676322 12731555 0 buyTicket 554 passenger229 3 3 4 5 5 true " +
				"12816840 12838329 0 inquiry 0 passenger328 3 0 3 5 11 true " +
				"12931132 12999132 0 buyTicket 682 passenger651 2 1 2 3 3 true " +
				"13090859 13115918 0 inquiry 0 passenger792 1 0 2 3 14 true " +
				"13197494 13275829 0 refundTicket 682 passenger651 2 1 2 3 3 true " +
				"13376580 13415658 0 buyTicket 810 passenger481 2 3 3 5 1 true " +
				"13515928 13536676 0 inquiry 0 passenger88 3 0 4 5 11 true " +
				"13616573 13634497 0 inquiry 0 passenger590 1 0 3 5 14 true " +
				"13722030 13740270 0 inquiry 0 passenger692 2 0 3 5 14 true ",

				"12409499 12765502 0 inquiry 0 passenger622 1 0 3 5 15 true " +
						"13028704 13064047 0 inquiry 0 passenger446 2 0 2 3 15 true " +
						"13125988 13413857 0 buyTicket 42 passenger584 2 1 4 5 3 true " +
						"13481081 13528861 0 refundTicket 42 passenger584 2 1 4 5 3 true " +
						"14214388 14238403 0 inquiry 0 passenger303 1 0 2 4 15 true " +
						"14294157 14310892 0 inquiry 0 passenger103 3 0 4 5 15 true " +
						"14365045 14379417 0 inquiry 0 passenger699 2 0 3 4 15 true ",

				"12174748 12796639 0 buyTicket 42 passenger648 2 1 1 4 3 true " +
						"13088963 13129328 0 buyTicket 170 passenger47 2 3 2 5 1 true " +
						"13185410 13300007 0 inquiry 0 passenger744 1 0 3 5 15 true " +
						"13360942 13374272 0 inquiry 0 passenger952 1 0 3 5 15 true " +
						"13477828 13490129 0 inquiry 0 passenger890 1 0 3 5 15 true " +
						"13696991 13711242 0 inquiry 0 passenger369 1 0 4 5 15 true " +
						"13766086 13792061 0 buyTicket 298 passenger117 2 2 4 5 4 true " +
						"13847212 13859827 0 inquiry 0 passenger231 3 0 2 5 15 true " +
						"13920113 13936268 0 inquiry 0 passenger917 3 0 3 4 15 true " +
						"13994208 14017271 0 buyTicket 426 passenger59 2 3 2 5 5 true " +
						"14119460 14155534 0 buyTicket 554 passenger348 3 1 3 4 3 true " +
						"14210176 14232917 0 buyTicket 682 passenger882 3 3 2 5 1 true " +
						"14283544 14304027 0 buyTicket 810 passenger431 2 2 1 5 1 true " +
						"14355343 14373510 0 inquiry 0 passenger422 2 0 3 4 11 true " +
						"14429742 14439138 0 inquiry 0 passenger245 2 0 1 5 10 true " +
						"14500751 14512817 0 inquiry 0 passenger262 2 0 1 5 10 true " +
						"14560374 14571422 0 inquiry 0 passenger788 2 0 1 3 11 true " +
						"14625108 14648214 0 buyTicket 938 passenger626 2 1 1 4 4 true " +
						"14698705 14723507 0 buyTicket 1066 passenger521 2 3 3 4 2 true " +
						"14772871 14797206 0 buyTicket 1194 passenger254 2 2 1 4 5 true " +
						"14845825 14891419 0 refundTicket 426 passenger59 2 3 2 5 5 true " +
						"14956197 14967661 0 inquiry 0 passenger746 3 0 2 5 13 true " +
						"15015483 15037674 0 buyTicket 1322 passenger302 3 2 1 2 4 true " +
						"15088103 15098284 0 inquiry 0 passenger816 3 0 1 5 12 true " +
						"15147521 15158840 0 inquiry 0 passenger89 1 0 1 3 15 true " +
						"15204919 15215280 0 inquiry 0 passenger190 2 0 3 5 8 true " +
						"15263871 15279673 0 refundTicket 298 passenger117 2 2 4 5 4 true " +
						"15351072 15373006 0 buyTicket 1450 passenger768 1 1 2 5 3 true " +
						"15438766 15470004 0 buyTicket 1578 passenger653 1 3 2 4 1 true " +
						"15519583 15534576 0 refundTicket 1322 passenger302 3 2 1 2 4 true " +
						"15584460 15594769 0 inquiry 0 passenger558 1 0 1 4 13 true " +
						"15641862 15674767 0 refundTicket 554 passenger348 3 1 3 4 3 true " +
						"15728119 15754586 0 buyTicket 1706 passenger306 1 2 4 5 4 true " +
						"15818918 15842170 0 buyTicket 1834 passenger696 2 2 1 5 4 true " +
						"15891974 15903327 0 inquiry 0 passenger652 1 0 3 5 12 true " +
						"15960041 15970793 0 inquiry 0 passenger17 2 0 3 5 8 true " +
						"16019105 16027903 0 inquiry 0 passenger827 2 0 1 5 8 true ",

				"12174748 12796639 0 buyTicket 42 passenger648 2 1 1 4 3 true " +
						"13088963 13129328 0 buyTicket 170 passenger47 2 3 2 5 1 true " +
						"13766086 13792061 0 buyTicket 298 passenger117 2 2 4 5 4 true " +
						"13994208 14017271 0 buyTicket 426 passenger59 2 3 2 5 5 true " +
						"14283544 14304027 0 buyTicket 810 passenger431 2 2 1 5 1 true " +
						"14625108 14648214 0 buyTicket 938 passenger626 2 1 1 4 4 true " +
						"14698705 14723507 0 buyTicket 1066 passenger521 2 3 3 4 2 true " +
						"14772871 14797206 0 buyTicket 1194 passenger254 2 2 1 4 5 true " +
						"14845825 14891419 0 refundTicket 426 passenger59 2 3 2 5 5 true " +
						"15263871 15279673 0 refundTicket 298 passenger117 2 2 4 5 4 true " +
						"15818918 15842170 0 buyTicket 1834 passenger696 2 2 1 5 4 true " +
						"16019105 16027903 0 inquiry 0 passenger827 2 0 1 5 8 true ",
		};
		int[] lines = { 17, 7, 37, 12 };
		replayHistory(historys[3], lines[3]);
		for (int i = 0; i < historys.length; ++i) {
			replayHistory(historys[i], lines[i]);
		}
	}

	private void replayHistory(String history, int line) {
		Config cfg = new Config(3, 3, 5, 5);
		final TicketingDS ds = new TicketingDS(cfg.routenum, cfg.coachnum, cfg.seatnum, cfg.stationnum, THREADNUM);
		String[] splittedHistory = history.split(" ");
		assertEquals(line * 12, splittedHistory.length);

		HashMap<RouteCoachSeat, Ticket> map = new HashMap<>();
		for (int i = 0; i < splittedHistory.length; i += 12) {
			String method = splittedHistory[i + 3];
			String passenger = splittedHistory[i + 5];
			int route = Integer.parseInt(splittedHistory[i + 6]);
			int coach = Integer.parseInt(splittedHistory[i + 7]);
			int departure = Integer.parseInt(splittedHistory[i + 8]);
			int arrival = Integer.parseInt(splittedHistory[i + 9]);
			int seat = Integer.parseInt(splittedHistory[i + 10]);
			boolean result = Boolean.parseBoolean(splittedHistory[i + 11]);
			executeHistory(method, ds, map, passenger, route, departure, arrival, coach, seat, result);
		}
	}

	static class RouteCoachSeat {
		int route;
		int coach;
		int seat;

		public RouteCoachSeat(int route, int coach, int seat) {
			this.route = route;
			this.coach = coach;
			this.seat = seat;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof RouteCoachSeat) {
				RouteCoachSeat other = (RouteCoachSeat) obj;
				return other == this || other.route == this.route && other.coach == this.coach && other.seat == this.seat;
			}
			return false;
		}

		@Override
		public int hashCode() {
			return route + coach * 1000 + seat * 1000000;
		}
	}

	private void executeHistory(String method, TicketingDS ds, HashMap<RouteCoachSeat, Ticket> map, String passenger,
			int route,
			int departure,
			int arrival,
			int coach, int seat, boolean result) {
		switch (method) {
			case "buyTicket":
				Ticket t = ds.buyTicket(passenger, route, departure, arrival);
				assertEquals(t != null, result);
				map.put(new RouteCoachSeat(route, coach, seat), t);
				break;
			case "inquiry":
				int remain = ds.inquiry(route, departure, arrival);
				assertEquals(remain, seat);
				break;
			case "refundTicket":
				t = map.get(new RouteCoachSeat(route, coach, seat));
				assertEquals(ds.refundTicket(t), result);
				break;
			default:
				throw new RuntimeException("invalid method");
		}
	}

	@Test
	void StampedLockTest1() {
		StampedLock lock = new StampedLock();
		long t1 = lock.tryOptimisticRead();
		long t2 = lock.writeLock();
		lock.unlockWrite(t2);
		assertEquals(0, lock.tryConvertToWriteLock(t1));
		t1 = lock.tryOptimisticRead();
		t2 = lock.tryOptimisticRead();
		long t3 = lock.tryConvertToWriteLock(t1);
		assertTrue(t3 > 0);
		lock.unlockWrite(t3);

		t1 = lock.tryOptimisticRead();
		t2 = lock.readLock();
		t3 = lock.tryConvertToWriteLock(t1);
		long t4 = lock.tryConvertToWriteLock(t2);
		assertEquals(0, t3);
		assertTrue(t4 > 0);
		lock.unlock(t4);

		t1 = lock.readLock();
		t2 = lock.readLock();
		t3 = lock.tryConvertToWriteLock(t1);
		assertEquals(0, t3);
		lock.unlockRead(t2);
		t3 = lock.tryConvertToWriteLock(t1);
		assertTrue(t3 > 0);
	}

	@Test
	void StampedLockTest2() {
		StampedLock lock = new StampedLock();
		long t2 = lock.writeLock();
		long t1 = lock.tryOptimisticRead();
		assertEquals(0, t1);
		lock.unlockWrite(t2);

		t1 = lock.tryOptimisticRead();
		assertNotEquals(0, t1);
	}
}