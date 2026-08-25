module shapes {
  type Option[a] = Some(a) | None

  type Status = Idle | Busy(int) | Named({ who: str, n: int })

  var aSet: Set[int]
  var aTup: (int, str)
  var aRec: { x: int, y: str }
  var anOpt: Option[int]
  var aStatus: Status
  var aBool: bool
  var aBigInt: int
  var nested: int -> Set[str]
  var aList: List[str]

  action init = all {
    aSet' = Set(3, 1, 2),
    aTup' = (7, "seven"),
    aRec' = { x: 1, y: "why" },
    anOpt' = Some(5),
    aStatus' = Busy(2),
    aBool' = true,
    aBigInt' = 9007199254740993,
    nested' = Map(1 -> Set("a"), 2 -> Set()),
    aList' = ["x", "y"],
  }

  action bump = all {
    aSet' = aSet.union(Set(4)),
    aTup' = aTup,
    aRec' = aRec,
    anOpt' = None,
    aStatus' = Named({ who: "bob", n: 3 }),
    aBool' = false,
    aBigInt' = aBigInt + 1,
    nested' = nested,
    aList' = aList.append("z"),
  }

  action step = bump
}
