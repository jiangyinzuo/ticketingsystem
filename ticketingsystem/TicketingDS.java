package ticketingsystem;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.StampedLock;
import sun.misc.Unsafe;

public class TicketingDS implements TicketingSystem {

	// @sun.misc.Contended
	static final class ContendedCell {
		long value;
		long pad1, pad2, pad3, pad4, pad5, pad6, pad7;

		ContendedCell(long x) {
			value = x;
		}
	}

	static final long TID_UNDETERMINED = -2;

	static final class TicketSale {
		long tid;
		String passenger;
		int coach;
		int seat;

		TicketSale(long tid, String passenger, int coach, int seat) {
			this.tid = tid;
			this.passenger = passenger;
			this.coach = coach;
			this.seat = seat;
		}

		TicketSale(int coach, int seat) {
			this(TID_UNDETERMINED, null, coach, seat);
		}

		TicketSale(Ticket ticket) {
			this.tid = ticket.tid;
			this.passenger = ticket.passenger;
			this.coach = ticket.coach;
			this.seat = ticket.seat;
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof TicketSale) {
				TicketSale t = (TicketSale) obj;
				return t == this || t.tid == tid && t.coach == coach && t.seat == seat
						&& (passenger == t.passenger || passenger != null && passenger.equals(t.passenger));
			}
			return false;
		}

		@Override
		public int hashCode() {
			return (((int) tid * 17 + coach) * 17 + seat) * 23;
		}
	}

	// 2PL
	// @sun.misc.Contended
	static final class LockedCell {
		final StampedLock rwlock = new StampedLock();
		final HashSet<TicketSale> ticketsOnSale = new HashSet<>(100);
		final ConcurrentHashMap<TicketSale, Object> soldTickets = new ConcurrentHashMap<>(100);
	}

	class RouteTickets {
		private final LockedCell[][] rangeSeatNums;
		// [coach][seat]
		private final AtomicLong[][] gapLocks;

		private void sleep0() {
			try {
				Thread.sleep(0);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		RouteTickets() {
			this.rangeSeatNums = new LockedCell[stationnum + 1][stationnum + 1];
			this.gapLocks = new AtomicLong[coachnum + 1][seatnum + 1];

			for (int i = 1; i <= stationnum; ++i) {
				for (int j = 1; j <= stationnum; ++j) {
					this.rangeSeatNums[i][j] = new LockedCell();
				}
			}
			for (int coach = 1; coach <= coachnum; ++coach) {
				for (int seat = 1; seat <= seatnum; ++seat) {
					this.rangeSeatNums[1][stationnum].ticketsOnSale.add(new TicketSale(coach, seat));
					this.gapLocks[coach][seat] = new AtomicLong(0);
				}
			}
		}

		TicketSale allocateCoachSeat(String passenger, int departure, int arrival) {
			TicketSale result = allocCoachSeatWithRange(passenger, departure, arrival, 1, stationnum);
			if (result != null) {
				return result;
			}
			for (int left = departure; left >= 1; --left) {
				for (int right = arrival; right <= stationnum; ++right) {
					result = allocCoachSeatWithRange(passenger, departure, arrival, left, right);
					if (result != null) {
						return result;
					}
				}
			}
			return null;
		}

		private TicketSale allocCoachSeatWithRange(String passenger, int departure, int arrival, int left, int right) {
			LockedCell oldCell = rangeSeatNums[left][right];
			for (;;) {
				long rs = oldCell.rwlock.tryOptimisticRead();
				if (!oldCell.ticketsOnSale.isEmpty()) {
					long oldwt = oldCell.rwlock.tryConvertToWriteLock(rs);
					if (oldwt == 0) {
						sleep0();
						continue;
					}
					TicketSale oldTicketOnSale = oldCell.ticketsOnSale.iterator().next();
					oldCell.ticketsOnSale.remove(oldTicketOnSale);
					LockedCell[] cells = { rangeSeatNums[left][departure],
							rangeSeatNums[arrival][right] };
					long[] wts = { 0, 0 };

					TicketSale newTicketSale = new TicketSale(genTid(), passenger, oldTicketOnSale.coach, oldTicketOnSale.seat);
					if (left < departure) {
						wts[0] = cells[0].rwlock.writeLock();
						cells[0].ticketsOnSale.add(new TicketSale(oldTicketOnSale.coach, oldTicketOnSale.seat));
					}
					if (arrival < right) {
						wts[1] = cells[1].rwlock.writeLock();
						cells[1].ticketsOnSale.add(new TicketSale(oldTicketOnSale.coach, oldTicketOnSale.seat));
					}

					AtomicLong soldBits = this.gapLocks[newTicketSale.coach][newTicketSale.seat];
					long bitvec = (2 << arrival) - (1 << (departure + 1));
					boolean result;
					do {
						long origin = soldBits.get();
						long updated = origin | bitvec;
						result = soldBits.compareAndSet(origin, updated);
					} while (!result);

					rangeSeatNums[departure][arrival].soldTickets.put(newTicketSale, new Object());
					oldCell.rwlock.unlockWrite(oldwt);
					for (int i = 0; i < wts.length; ++i) {
						if (wts[i] > 0) {
							cells[i].rwlock.unlockWrite(wts[i]);
						}
					}

					return newTicketSale;
				} else {
					return null;
				}
			}
		}

		boolean freeCoachSeat(Ticket ticket) {
			LockedCell cell = rangeSeatNums[ticket.departure][ticket.arrival];
			TicketSale soldTicketSale = new TicketSale(ticket);
			if (cell.soldTickets.remove(soldTicketSale) == null) {
				return false;
			}
			AtomicLong soldBits = this.gapLocks[ticket.coach][ticket.seat];
			boolean end;
			do {
				long ticketBits = (2 << ticket.arrival) - (1 << (ticket.departure + 1));
				long origin = soldBits.get();
				long update = origin ^ ticketBits;

				int left = ticket.departure;
				while (left > 1 && (update & (1 << (left))) == 0) {
					--left;
				}
				int right = ticket.arrival;
				while (right < stationnum && (update & (1 << (right + 1))) == 0) {
					++right;
				}

				LockedCell[] cells = { rangeSeatNums[left][right], rangeSeatNums[left][ticket.departure],
						rangeSeatNums[ticket.arrival][right] };
				long[] wts = { 0, 0, 0 };
				wts[0] = cells[0].rwlock.writeLock();
				if (left < ticket.departure) {
					wts[1] = cells[1].rwlock.writeLock();
				}
				if (ticket.arrival < right) {
					wts[2] = cells[2].rwlock.writeLock();
				}

				long validate = soldBits.get();
				end = ((origin ^ validate) & ((2 << right) - (1 << (left + 1)))) == 0;
				if (end) {
					TicketSale availablTicketSale = new TicketSale(ticket.coach, ticket.seat);
					if (left < ticket.departure) {
						cells[1].ticketsOnSale.remove(availablTicketSale);
					}
					if (ticket.arrival < right) {
						cells[2].ticketsOnSale.remove(availablTicketSale);
					}
					cells[0].ticketsOnSale.add(availablTicketSale);

					while (!soldBits.compareAndSet(validate, validate & ~ticketBits)) {
						validate = soldBits.get();
					}
				}
				for (int i = 0; i < wts.length; ++i) {
					if (wts[i] > 0) {
						cells[i].rwlock.unlockWrite(wts[i]);
					}
				}
			} while (!end);
			return true;
		}

		int queryCoachSeatNum(int departure, int arrival) {
			int sum = 0;
			Lock[] readlocks = new Lock[departure * (stationnum - arrival + 1)];

			int i = 0;
			for (int left = 1; left <= departure; ++left) {
				for (int right = stationnum; right >= arrival; --right) {
					LockedCell cell = rangeSeatNums[left][right];
					long rt = cell.rwlock.tryOptimisticRead();
					int sz = cell.ticketsOnSale.size();
					if (cell.rwlock.validate(rt)) {
						sum += sz;
					} else {
						readlocks[i] = cell.rwlock.asReadLock();
						readlocks[i].lock();
						sum += cell.ticketsOnSale.size();
					}
					++i;
				}
			}

			for (Lock lock : readlocks) {
				if (lock != null) {
					lock.unlock();
				}
			}
			assert sum <= coachnum * seatnum;
			return sum;
		}
	}

	private static final int MAX_THREAD_NUM = 512;
	ContendedCell[] threadLocalCounterCell = new ContendedCell[MAX_THREAD_NUM];

	private final int routenum;
	private final int coachnum;
	private final int seatnum;
	private final int stationnum;
	private final int threadnum;
	private final RouteTickets[] routeTickets;
	private Unsafe unsafe = null;

	public TicketingDS(int routenum, int coachnum, int seatnum, int stationnum, int threadnum) {
		assert routenum > 0;
		assert coachnum > 0;
		assert seatnum > 0;
		assert stationnum > 0;
		assert 0 < threadnum && threadnum <= MAX_THREAD_NUM;
		this.routenum = routenum;
		this.coachnum = coachnum;
		this.seatnum = seatnum;
		this.stationnum = stationnum;
		this.threadnum = threadnum;
		for (int i = 0; i < MAX_THREAD_NUM; ++i) {
			threadLocalCounterCell[i] = new ContendedCell(0);
		}
		routeTickets = new RouteTickets[routenum];
		for (int i = 0; i < routenum; ++i) {
			routeTickets[i] = new RouteTickets();
		}

		try {
			Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
			unsafeField.setAccessible(true);
			unsafe = (Unsafe) unsafeField.get(null);
			unsafe.storeFence();
		} catch (IllegalAccessException | NoSuchFieldException e) {
			e.printStackTrace();
		}
	}

	private long genTid() {
		long threadID = Thread.currentThread().getId();
		assert threadID < MAX_THREAD_NUM;
		long threadLocalTID = threadLocalCounterCell[(int) threadID].value++;
		return threadLocalTID * MAX_THREAD_NUM + threadID;
	}

	private Ticket newTicket(int route, int departure, int arrival,
			TicketSale ticketOnSale) {
		Ticket ticket = new Ticket();
		assert ticketOnSale.tid != TID_UNDETERMINED;
		assert ticketOnSale.passenger != null;

		ticket.tid = ticketOnSale.tid;
		ticket.passenger = ticketOnSale.passenger;
		ticket.route = route;
		ticket.coach = ticketOnSale.coach;
		ticket.seat = ticketOnSale.seat;
		ticket.departure = departure;
		ticket.arrival = arrival;
		return ticket;
	}

	@Override
	public Ticket buyTicket(String passenger, int route, int departure, int arrival) {
		if (1 <= departure && departure < arrival && arrival <= stationnum && 1 <= route && route <= routenum) {
			TicketSale ticket = routeTickets[route - 1].allocateCoachSeat(passenger, departure, arrival);
			if (ticket != null) {
				return newTicket(route, departure, arrival, ticket);
			}
		}
		return null;
	}

	@Override
	public int inquiry(int route, int departure, int arrival) {
		if (1 <= departure && departure < arrival && arrival <= stationnum && 1 <= route && route <= routenum) {
			return routeTickets[route - 1].queryCoachSeatNum(departure, arrival);
		}
		return 0;
	}

	@Override
	public boolean refundTicket(Ticket ticket) {
		if (ticket != null && 1 <= ticket.route && ticket.route <= routenum) {
			return routeTickets[ticket.route - 1].freeCoachSeat(ticket);
		} else {
			return false;
		}
	}

	@Override
	public boolean buyTicketReplay(Ticket ticket) {
		assert ticket != null;
		Ticket t = buyTicket(ticket.passenger, ticket.route, ticket.departure, ticket.arrival);
		return ticket.equals(t);
	}

	@Override
	public boolean refundTicketReplay(Ticket ticket) {
		return refundTicket(ticket);
	}
}