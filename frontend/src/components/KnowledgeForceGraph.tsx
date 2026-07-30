import { useEffect, useMemo, useRef, useState, type MouseEvent } from 'react'

export type GraphVizNode = {
  id: string
  type?: string
  name?: string
  filePath?: string
  startLine?: number | null
  language?: string
}

export type GraphVizEdge = {
  id?: string
  source: string
  target: string
  type?: string
}

type SimNode = GraphVizNode & {
  x: number
  y: number
  vx: number
  vy: number
}

const TYPE_COLORS: Record<string, string> = {
  repository: '#0969da',
  directory: '#6e7781',
  file: '#1a7f37',
  function: '#8250df',
  method: '#8250df',
  class: '#bf3989',
  interface: '#bf3989',
  schema: '#9a6700',
  config: '#cf222e',
  community: '#0550ae',
}

type Props = {
  nodes: GraphVizNode[]
  edges: GraphVizEdge[]
  height?: number
}

export default function KnowledgeForceGraph({ nodes, edges, height = 520 }: Props) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [typeFilter, setTypeFilter] = useState<string>('all')
  const simRef = useRef<SimNode[]>([])
  const edgeIndexRef = useRef<Array<{ s: number; t: number; type: string }>>([])

  const types = useMemo(() => {
    const set = new Set<string>()
    nodes.forEach((n) => {
      if (n.type) set.add(n.type)
    })
    return Array.from(set).sort()
  }, [nodes])

  const filtered = useMemo(() => {
    const keep = new Set(
      nodes
        .filter((n) => typeFilter === 'all' || n.type === typeFilter)
        .map((n) => n.id),
    )
    // Keep neighbors of filtered types so edges remain meaningful when filtering.
    if (typeFilter !== 'all') {
      edges.forEach((e) => {
        if (keep.has(e.source) || keep.has(e.target)) {
          keep.add(e.source)
          keep.add(e.target)
        }
      })
    }
    const visibleNodes = nodes.filter((n) => keep.has(n.id))
    const idSet = new Set(visibleNodes.map((n) => n.id))
    const visibleEdges = edges.filter((e) => idSet.has(e.source) && idSet.has(e.target))
    return { visibleNodes, visibleEdges }
  }, [nodes, edges, typeFilter])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const width = canvas.clientWidth || 900
    canvas.width = width * devicePixelRatio
    canvas.height = height * devicePixelRatio
    const ctx = canvas.getContext('2d')
    if (!ctx) return
    ctx.setTransform(devicePixelRatio, 0, 0, devicePixelRatio, 0, 0)

    const sim: SimNode[] = filtered.visibleNodes.map((n, i) => {
      const angle = (2 * Math.PI * i) / Math.max(filtered.visibleNodes.length, 1)
      const radius = 40 + Math.min(180, filtered.visibleNodes.length)
      return {
        ...n,
        x: width / 2 + Math.cos(angle) * radius + (Math.random() - 0.5) * 20,
        y: height / 2 + Math.sin(angle) * radius + (Math.random() - 0.5) * 20,
        vx: 0,
        vy: 0,
      }
    })
    const index = new Map(sim.map((n, i) => [n.id, i]))
    const links = filtered.visibleEdges
      .map((e) => ({
        s: index.get(e.source),
        t: index.get(e.target),
        type: e.type || '',
      }))
      .filter((e): e is { s: number; t: number; type: string } => e.s != null && e.t != null)

    simRef.current = sim
    edgeIndexRef.current = links

    let frame = 0
    let raf = 0
    const tick = () => {
      frame += 1
      const alpha = Math.max(0.02, 1 - frame / 280)
      // repulsion
      for (let i = 0; i < sim.length; i++) {
        for (let j = i + 1; j < sim.length; j++) {
          const a = sim[i]
          const b = sim[j]
          let dx = a.x - b.x
          let dy = a.y - b.y
          let dist2 = dx * dx + dy * dy
          if (dist2 < 25) dist2 = 25
          const force = (900 * alpha) / dist2
          const dist = Math.sqrt(dist2)
          dx /= dist
          dy /= dist
          a.vx += dx * force
          a.vy += dy * force
          b.vx -= dx * force
          b.vy -= dy * force
        }
      }
      // springs
      for (const link of links) {
        const a = sim[link.s]
        const b = sim[link.t]
        const dx = b.x - a.x
        const dy = b.y - a.y
        const dist = Math.max(1, Math.sqrt(dx * dx + dy * dy))
        const desired = 70
        const force = ((dist - desired) * 0.03) * alpha
        const fx = (dx / dist) * force
        const fy = (dy / dist) * force
        a.vx += fx
        a.vy += fy
        b.vx -= fx
        b.vy -= fy
      }
      // center gravity
      for (const n of sim) {
        n.vx += ((width / 2) - n.x) * 0.005 * alpha
        n.vy += ((height / 2) - n.y) * 0.005 * alpha
        n.vx *= 0.85
        n.vy *= 0.85
        n.x += n.vx
        n.y += n.vy
        n.x = Math.min(width - 8, Math.max(8, n.x))
        n.y = Math.min(height - 8, Math.max(8, n.y))
      }

      ctx.clearRect(0, 0, width, height)
      ctx.fillStyle = '#f6f8fa'
      ctx.fillRect(0, 0, width, height)

      ctx.strokeStyle = 'rgba(87, 96, 106, 0.25)'
      ctx.lineWidth = 1
      for (const link of links) {
        const a = sim[link.s]
        const b = sim[link.t]
        ctx.beginPath()
        ctx.moveTo(a.x, a.y)
        ctx.lineTo(b.x, b.y)
        ctx.stroke()
      }

      for (const n of sim) {
        const color = TYPE_COLORS[n.type || ''] || '#57606a'
        const r = n.type === 'repository' ? 7 : n.type === 'file' || n.type === 'directory' ? 5 : 4
        ctx.beginPath()
        ctx.fillStyle = n.id === selectedId ? '#d1242f' : color
        ctx.arc(n.x, n.y, r, 0, Math.PI * 2)
        ctx.fill()
        if (n.id === selectedId || (n.type === 'repository')) {
          ctx.fillStyle = '#24292f'
          ctx.font = '11px sans-serif'
          ctx.fillText(n.name || n.id.slice(-16), n.x + 6, n.y - 6)
        }
      }

      if (frame < 320) {
        raf = requestAnimationFrame(tick)
      }
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [filtered, height, selectedId])

  const selected = filtered.visibleNodes.find((n) => n.id === selectedId) || null

  const onClick = (ev: MouseEvent<HTMLCanvasElement>) => {
    const canvas = canvasRef.current
    if (!canvas) return
    const rect = canvas.getBoundingClientRect()
    const x = ev.clientX - rect.left
    const y = ev.clientY - rect.top
    let best: SimNode | null = null
    let bestDist = 12
    for (const n of simRef.current) {
      const d = Math.hypot(n.x - x, n.y - y)
      if (d < bestDist) {
        best = n
        bestDist = d
      }
    }
    setSelectedId(best?.id ?? null)
  }

  return (
    <div>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginBottom: 8, alignItems: 'center' }}>
        <label className="gh-muted" style={{ fontSize: 12 }}>
          类型过滤
          <select
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
            style={{ marginLeft: 6 }}
          >
            <option value="all">全部 ({nodes.length} 节点)</option>
            {types.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
        <span className="gh-label">
          显示 {filtered.visibleNodes.length} 节点 / {filtered.visibleEdges.length} 边
        </span>
      </div>
      <canvas
        ref={canvasRef}
        onClick={onClick}
        style={{
          width: '100%',
          height,
          border: '1px solid var(--border)',
          borderRadius: 6,
          cursor: 'crosshair',
          display: 'block',
        }}
      />
      {selected && (
        <pre
          style={{
            marginTop: 8,
            padding: 12,
            background: '#f6f8fa',
            border: '1px solid var(--border)',
            borderRadius: 6,
            fontSize: 12,
            overflow: 'auto',
          }}
        >
          {JSON.stringify(selected, null, 2)}
        </pre>
      )}
      {!selected && (
        <p className="gh-muted" style={{ margin: '8px 0 0', fontSize: 12 }}>
          点击节点查看 CodeWiki 返回的原始字段（id / type / name / filePath）。
        </p>
      )}
    </div>
  )
}
