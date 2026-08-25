// A step with one anonymous branch, recorded to pin what "anonymous" actually
// means to Quint.
//
// mbt::actionTaken reports the first *named* action the branch executed. So a
// branch built out of named actions is not anonymous at all — `all { bump,
// touch }` records "bump" — and a named `step` records "step". The empty
// string appears only when the branch that fired contains no named action
// anywhere in it, which is what `raise` below is: bare assignments, inline.
//
// There is nothing for a driver to dispatch on then, and the fix belongs in
// the spec: name the combination, the way `idle` is named here.
module anonymous {
  var n: int
  var touched: bool

  action idle = all { n' = n, touched' = touched }

  action init = all { n' = 0, touched' = false }

  action step = any {
    all { n' = n + 1, touched' = true },   // no named action inside -> ""
    idle,                                  // named                 -> "idle"
  }
}
