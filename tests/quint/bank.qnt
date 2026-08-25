module bank {
  type Account = str
  const ACCOUNTS: Set[Account]

  var balances: Account -> int
  var lastError: str

  action init = all {
    balances' = ACCOUNTS.mapBy(_ => 0),
    lastError' = "",
  }

  action deposit(who: Account, amount: int): bool = all {
    amount > 0,
    balances' = balances.setBy(who, b => b + amount),
    lastError' = "",
  }

  action withdraw(who: Account, amount: int): bool = all {
    amount > 0,
    balances.get(who) >= amount,
    balances' = balances.setBy(who, b => b - amount),
    lastError' = "",
  }

  action overdraft(who: Account, amount: int): bool = all {
    amount > 0,
    balances.get(who) < amount,
    balances' = balances,
    lastError' = "insufficient funds",
  }

  action step = {
    nondet who = ACCOUNTS.oneOf()
    nondet amount = 1.to(100).oneOf()
    any {
      deposit(who, amount),
      withdraw(who, amount),
      overdraft(who, amount),
    }
  }

  val noNegatives = ACCOUNTS.forall(a => balances.get(a) >= 0)
}

module bankTest {
  import bank(ACCOUNTS = Set("alice", "bob")).*
}

module bankRuns {
  import bank(ACCOUNTS = Set("alice", "bob")).*

  run depositThenWithdrawTest =
    init
      .then(deposit("alice", 50))
      .then(withdraw("alice", 20))
      .expect(balances.get("alice") == 30)
}
