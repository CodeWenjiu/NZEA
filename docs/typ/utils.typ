#let colors = (
  orange: rgb(245, 180, 147),
  yellow: rgb(250, 225, 127),
  green: rgb(127, 200, 172),
  pink: rgb(236, 127, 178),
  purple: rgb(189, 151, 255),
  blue: rgb(127, 203, 235)
)

#let hrule(length: 100%, stroke: gray + 1pt) = {
  line(length: length, stroke: stroke)
}

#let bifig(body, capzh, kind) = figure(
  body,
  supplement: none,
  kind: kind,
  caption: metadata(capzh),
)
