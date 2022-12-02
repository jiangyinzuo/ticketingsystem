package ticketingsystem;

public class Test {

	public static void main(String[] args) throws InterruptedException {
		final TicketingDS tds = new TicketingDS(5, 8, 100, 10, 16);
		Ticket ticket = tds.buyTicket(null, 0, 0, 0);
		System.out.println(ticket);
		for (int i = 0; i < 105; ++i) {
			ticket = tds.buyTicket(null, 0, 1, 10);
			System.out.println(ticket);
		}
	}
}
