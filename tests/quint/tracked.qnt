// The same bank as bank.qnt, except that it records what it just did in two
// ordinary state variables. Neither `quint test` nor `quint verify` emits the
// mbt:: variables, so a spec that wants its traces to drive an implementation
// has to track the action itself — which is also how Choreo-style specs are
// written. dev/bank/core.clj replays this spec unchanged: :action-path and
// :nondet-path split lastAction and lastPick out of the compared state, so the
// implementation is never asked to supply the spec's own bookkeeping.
module tracked {
  type Account = str
  const ACCOUNTS: Set[Account]

  var balances: Account -> int
  var lastError: str
  var lastAction: str
  var lastPick: { who: Account, amount: int }

  action init = all {
    balances' = ACCOUNTS.mapBy(_ => 0),
    lastError' = "",
    lastAction' = "init",
    lastPick' = { who: "", amount: 0 },
  }

  action deposit(who: Account, amount: int): bool = all {
    amount > 0,
    balances' = balances.setBy(who, b => b + amount),
    lastError' = "",
    lastAction' = "deposit",
    lastPick' = { who: who, amount: amount },
  }

  action withdraw(who: Account, amount: int): bool = all {
    amount > 0,
    balances.get(who) >= amount,
    balances' = balances.setBy(who, b => b - amount),
    lastError' = "",
    lastAction' = "withdraw",
    lastPick' = { who: who, amount: amount },
  }

  action overdraft(who: Account, amount: int): bool = all {
    amount > 0,
    balances.get(who) < amount,
    balances' = balances,
    lastError' = "insufficient funds",
    lastAction' = "overdraft",
    lastPick' = { who: who, amount: amount },
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

  // Violated by one deposit of more than 50, which is what makes it worth
  // recording: Apalache finds it at step 1 instead of after minutes of search.
  val underFifty = ACCOUNTS.forall(a => balances.get(a) <= 50)
}

module trackedTest {
  import tracked(ACCOUNTS = Set("alice", "bob")).*
}

// A run whose expectation does not hold, kept in its own module so it is
// reachable only on purpose. It is what proves that a broken spec and a broken
// implementation produce different errors.
module trackedBroken {
  import tracked(ACCOUNTS = Set("alice", "bob")).*

  run brokenTest =
    init
      .then(deposit("alice", 50))
      .expect(balances.get("alice") == 999)
}

module trackedRuns {
  import tracked(ACCOUNTS = Set("alice", "bob")).*

  run depositThenOverdraftTest =
    init
      .then(deposit("alice", 50))
      .then(withdraw("alice", 20))
      .then(overdraft("bob", 5))
      .expect(balances.get("alice") == 30 and lastError == "insufficient funds")
}
