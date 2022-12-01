package ticketingsystem;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Test;

import ticketingsystem.TicketingDS.TicketSale;

class TicketSaleTest {
	@Test
	void hashTest() {
		ConcurrentHashMap<TicketSale, Object> map = new ConcurrentHashMap<>();
		TicketSale t = new TicketSale(1, "alice", 2, 3);
		map.put(t, new Object());
		assertNotNull(map.get(t));
	}
}
