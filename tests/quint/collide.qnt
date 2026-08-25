module counter {
  const START: int
  var n: int

  action init = all { n' = START }
  action step = all { n' = n + 1 }
}

module collide {
  import counter(START = 1) as left
  import counter(START = 100) as right

  action init = all { left::init, right::init }
  action step = all { left::step, right::step }
}
