/**
 * Pure TypeScript ISO/IEC 18004 QR Code encoder and SVG generator.
 * Computes Byte-mode bitstreams, Reed-Solomon ECC, masking, and SVG paths locally
 * so otpauth:// TOTP secrets never leave the browser.
 */

export type QrErrorCorrectionLevel = 'L' | 'M'

export type QrMatrix = {
  version: number
  size: number
  ecc: QrErrorCorrectionLevel
  modules: boolean[][]
}

export type QrSvgModel = {
  version: number
  size: number
  margin: number
  totalSize: number
  viewBox: string
  pathD: string
  label: string
}

type EcParams = readonly [ecCodewordsPerBlock: number, numBlocks: number]

// ISO/IEC 18004 Table 9 error correction parameters for Versions 1..20 (Levels L and M).
const EC_TABLE: Record<QrErrorCorrectionLevel, readonly EcParams[]> = {
  L: [
    [7, 1], [10, 1], [15, 1], [20, 1], [26, 1],
    [18, 2], [20, 2], [24, 2], [30, 2], [18, 4],
    [20, 4], [24, 4], [26, 4], [30, 4], [22, 6],
    [24, 6], [28, 6], [30, 6], [28, 7], [28, 8]
  ],
  M: [
    [10, 1], [16, 1], [26, 1], [18, 2], [24, 2],
    [16, 4], [18, 4], [22, 4], [22, 5], [26, 5],
    [30, 5], [22, 8], [22, 9], [24, 9], [24, 10],
    [28, 10], [28, 11], [26, 13], [26, 14], [26, 16]
  ]
}

const ECC_FORMAT_BITS: Record<QrErrorCorrectionLevel, number> = {
  M: 0,
  L: 1
}

const MAX_VERSION = 20

function gfMultiply(x: number, y: number): number {
  let result = 0
  for (let bit = 7; bit >= 0; bit--) {
    result = (result << 1) ^ ((result >>> 7) * 0x11d)
    if (((y >>> bit) & 1) !== 0) {
      result ^= x
    }
  }
  return result
}

function reedSolomonDivisor(degree: number): Uint8Array {
  const result = new Uint8Array(degree)
  result[degree - 1] = 1
  let root = 1
  for (let i = 0; i < degree; i++) {
    for (let j = 0; j < degree; j++) {
      result[j] = gfMultiply(result[j], root)
      if (j + 1 < degree) {
        result[j] ^= result[j + 1]
      }
    }
    root = gfMultiply(root, 0x02)
  }
  return result
}

function reedSolomonRemainder(data: Uint8Array, divisor: Uint8Array): Uint8Array {
  const result = new Uint8Array(divisor.length)
  for (const byte of data) {
    const factor = byte ^ result[0]
    result.copyWithin(0, 1)
    result[result.length - 1] = 0
    for (let i = 0; i < divisor.length; i++) {
      result[i] ^= gfMultiply(divisor[i], factor)
    }
  }
  return result
}

function numRawDataModules(version: number): number {
  const size = 4 * version + 17
  let result = size * size - 192 - (8 * version + 2) - 31
  if (version >= 7) {
    result -= 36
  }
  if (version >= 2) {
    const numAlign = Math.floor(version / 7) + 2
    result -= (numAlign * numAlign - 3) * 25 - (numAlign - 2) * 10
  }
  return result
}

function numDataCodewords(version: number, ecc: QrErrorCorrectionLevel): number {
  const [ecPerBlock, numBlocks] = EC_TABLE[ecc][version - 1]
  return Math.floor(numRawDataModules(version) / 8) - ecPerBlock * numBlocks
}

function charCountBitsByteMode(version: number): number {
  return version <= 9 ? 8 : 16
}

function encodeByteSegments(payload: Uint8Array, version: number, capacityBytes: number): Uint8Array {
  const bits: number[] = []
  const pushBits = (val: number, len: number) => {
    for (let i = len - 1; i >= 0; i--) {
      bits.push((val >>> i) & 1)
    }
  }

  // Mode indicator 0100 (Byte mode)
  pushBits(0b0100, 4)
  pushBits(payload.length, charCountBitsByteMode(version))
  for (const byte of payload) {
    pushBits(byte, 8)
  }

  const capacityBits = capacityBytes * 8
  const terminatorLen = Math.min(4, capacityBits - bits.length)
  pushBits(0, terminatorLen)

  while (bits.length % 8 !== 0) {
    bits.push(0)
  }

  const codewords = new Uint8Array(capacityBytes)
  const dataLenBytes = bits.length / 8
  for (let i = 0; i < dataLenBytes; i++) {
    let byte = 0
    for (let b = 0; b < 8; b++) {
      byte = (byte << 1) | bits[i * 8 + b]
    }
    codewords[i] = byte
  }

  for (let i = dataLenBytes; i < capacityBytes; i++) {
    codewords[i] = (i - dataLenBytes) % 2 === 0 ? 0xec : 0x11
  }

  return codewords
}

function interleaveCodewords(data: Uint8Array, version: number, ecc: QrErrorCorrectionLevel): Uint8Array {
  const rawTotal = Math.floor(numRawDataModules(version) / 8)
  const [ecPerBlock, numBlocks] = EC_TABLE[ecc][version - 1]
  const numShortBlocks = numBlocks - (rawTotal % numBlocks)
  const shortBlockLen = Math.floor(rawTotal / numBlocks)
  const divisor = reedSolomonDivisor(ecPerBlock)

  const dataBlocks: Uint8Array[] = []
  const ecBlocks: Uint8Array[] = []
  const shortDataLen = shortBlockLen - ecPerBlock
  let offset = 0
  for (let i = 0; i < numBlocks; i++) {
    const dataLen = shortDataLen + (i < numShortBlocks ? 0 : 1)
    const slice = data.subarray(offset, offset + dataLen)
    offset += dataLen
    dataBlocks.push(slice)
    ecBlocks.push(reedSolomonRemainder(slice, divisor))
  }

  const result = new Uint8Array(rawTotal)
  let outIdx = 0
  const maxDataLen = shortDataLen + (numShortBlocks < numBlocks ? 1 : 0)
  for (let col = 0; col < maxDataLen; col++) {
    for (let b = 0; b < numBlocks; b++) {
      if (col < dataBlocks[b].length) {
        result[outIdx++] = dataBlocks[b][col]
      }
    }
  }
  for (let col = 0; col < ecPerBlock; col++) {
    for (let b = 0; b < numBlocks; b++) {
      result[outIdx++] = ecBlocks[b][col]
    }
  }
  return result
}

function alignmentPatternPositions(version: number): number[] {
  if (version === 1) return []
  const numAlign = Math.floor(version / 7) + 2
  const step = version === 32 ? 26 : Math.ceil((version * 4 + 4) / (numAlign * 2 - 2)) * 2
  const positions = [6]
  for (let pos = version * 4 + 10; positions.length < numAlign; pos -= step) {
    positions.splice(1, 0, pos)
  }
  return positions
}

function drawFinderPattern(
  modules: boolean[][],
  isFunction: boolean[][],
  centerRow: number,
  centerCol: number
): void {
  const size = modules.length
  for (let dy = -4; dy <= 4; dy++) {
    for (let dx = -4; dx <= 4; dx++) {
      const r = centerRow + dy
      const c = centerCol + dx
      if (r < 0 || r >= size || c < 0 || c >= size) continue
      const dist = Math.max(Math.abs(dy), Math.abs(dx))
      const dark = dist !== 2 && dist !== 4
      modules[r][c] = dark
      isFunction[r][c] = true
    }
  }
}

function drawAlignmentPattern(
  modules: boolean[][],
  isFunction: boolean[][],
  centerRow: number,
  centerCol: number
): void {
  for (let dy = -2; dy <= 2; dy++) {
    for (let dx = -2; dx <= 2; dx++) {
      const dist = Math.max(Math.abs(dy), Math.abs(dx))
      modules[centerRow + dy][centerCol + dx] = dist !== 1
      isFunction[centerRow + dy][centerCol + dx] = true
    }
  }
}

function drawFormatBits(
  modules: boolean[][],
  isFunction: boolean[][],
  ecc: QrErrorCorrectionLevel,
  mask: number
): void {
  const size = modules.length
  const data = (ECC_FORMAT_BITS[ecc] << 3) | mask
  let rem = data
  for (let i = 0; i < 10; i++) {
    rem = (rem << 1) ^ (((rem >>> 9) & 1) * 0x537)
  }
  const bits = ((data << 10) | rem) ^ 0x5412

  const setFn = (r: number, c: number, dark: boolean) => {
    modules[r][c] = dark
    isFunction[r][c] = true
  }

  for (let i = 0; i <= 5; i++) setFn(i, 8, ((bits >>> i) & 1) !== 0)
  setFn(7, 8, ((bits >>> 6) & 1) !== 0)
  setFn(8, 8, ((bits >>> 7) & 1) !== 0)
  setFn(8, 7, ((bits >>> 8) & 1) !== 0)
  for (let i = 9; i < 15; i++) setFn(8, 14 - i, ((bits >>> i) & 1) !== 0)

  for (let i = 0; i < 8; i++) setFn(8, size - 1 - i, ((bits >>> i) & 1) !== 0)
  for (let i = 8; i < 15; i++) setFn(size - 15 + i, 8, ((bits >>> i) & 1) !== 0)
  setFn(size - 8, 8, true)
}

function drawVersionBits(modules: boolean[][], isFunction: boolean[][], version: number): void {
  if (version < 7) return
  const size = modules.length
  let rem = version
  for (let i = 0; i < 12; i++) {
    rem = (rem << 1) ^ (((rem >>> 11) & 1) * 0x1f25)
  }
  const bits = (version << 12) | rem
  for (let i = 0; i < 18; i++) {
    const dark = ((bits >>> i) & 1) !== 0
    const a = size - 11 + (i % 3)
    const b = Math.floor(i / 3)
    modules[a][b] = dark
    isFunction[a][b] = true
    modules[b][a] = dark
    isFunction[b][a] = true
  }
}

function applyMask(modules: boolean[][], isFunction: boolean[][], mask: number): void {
  const size = modules.length
  for (let r = 0; r < size; r++) {
    for (let c = 0; c < size; c++) {
      if (isFunction[r][c]) continue
      let invert = false
      switch (mask) {
        case 0: invert = (r + c) % 2 === 0; break
        case 1: invert = r % 2 === 0; break
        case 2: invert = c % 3 === 0; break
        case 3: invert = (r + c) % 3 === 0; break
        case 4: invert = (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0; break
        case 5: invert = ((r * c) % 2) + ((r * c) % 3) === 0; break
        case 6: invert = (((r * c) % 2) + ((r * c) % 3)) % 2 === 0; break
        case 7: invert = (((r + c) % 2) + ((r * c) % 3)) % 2 === 0; break
      }
      if (invert) modules[r][c] = !modules[r][c]
    }
  }
}

function finderPenaltyCountPatterns(runHistory: number[]): number {
  const n = runHistory[1]
  const core = n > 0 && runHistory[2] === n && runHistory[3] === n * 3 && runHistory[4] === n && runHistory[5] === n
  return (core && runHistory[0] >= n * 4 && runHistory[6] >= n ? 1 : 0)
    + (core && runHistory[6] >= n * 4 && runHistory[0] >= n ? 1 : 0)
}

function finderPenaltyTerminateAndCount(currentRunColor: boolean, currentRunLength: number, runHistory: number[], size: number): number {
  if (currentRunColor) {
    runHistory.pop()
    runHistory.unshift(currentRunLength)
    currentRunLength = 0
  }
  currentRunLength += size
  runHistory.pop()
  runHistory.unshift(currentRunLength)
  return finderPenaltyCountPatterns(runHistory)
}

function evaluatePenalty(modules: boolean[][]): number {
  const size = modules.length
  let result = 0

  // N1 & N3 horizontal
  for (let r = 0; r < size; r++) {
    let runColor = false
    let runLen = 0
    const runHistory = [0, 0, 0, 0, 0, 0, 0]
    for (let c = 0; c < size; c++) {
      if (modules[r][c] === runColor) {
        runLen++
        if (runLen === 5) result += 3
        else if (runLen > 5) result++
      } else {
        runHistory.pop()
        runHistory.unshift(runLen)
        if (!runColor) {
          result += finderPenaltyCountPatterns(runHistory) * 40
        }
        runColor = modules[r][c]
        runLen = 1
      }
    }
    result += finderPenaltyTerminateAndCount(runColor, runLen, runHistory, size) * 40
  }

  // N1 & N3 vertical
  for (let c = 0; c < size; c++) {
    let runColor = false
    let runLen = 0
    const runHistory = [0, 0, 0, 0, 0, 0, 0]
    for (let r = 0; r < size; r++) {
      if (modules[r][c] === runColor) {
        runLen++
        if (runLen === 5) result += 3
        else if (runLen > 5) result++
      } else {
        runHistory.pop()
        runHistory.unshift(runLen)
        if (!runColor) {
          result += finderPenaltyCountPatterns(runHistory) * 40
        }
        runColor = modules[r][c]
        runLen = 1
      }
    }
    result += finderPenaltyTerminateAndCount(runColor, runLen, runHistory, size) * 40
  }

  // N2 2x2 blocks
  for (let r = 0; r < size - 1; r++) {
    for (let c = 0; c < size - 1; c++) {
      const color = modules[r][c]
      if (color === modules[r][c + 1] && color === modules[r + 1][c] && color === modules[r + 1][c + 1]) {
        result += 3
      }
    }
  }

  // N4 dark module balance
  let dark = 0
  for (const row of modules) {
    for (const cell of row) {
      if (cell) dark++
    }
  }
  const total = size * size
  const k = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1
  result += k * 10
  return result
}

export function encodeQrMatrix(text: string, preferredEcc: QrErrorCorrectionLevel = 'M'): QrMatrix {
  if (!text) {
    throw new Error('QR 코드로 인코딩할 텍스트가 비어 있습니다.')
  }
  const payload = new TextEncoder().encode(text)

  let selectedVersion = 0
  let selectedEcc: QrErrorCorrectionLevel = preferredEcc
  for (const ecc of (preferredEcc === 'M' ? (['M', 'L'] as const) : (['L', 'M'] as const))) {
    for (let v = 1; v <= MAX_VERSION; v++) {
      const headerBits = 4 + charCountBitsByteMode(v)
      const dataCapacity = numDataCodewords(v, ecc)
      if (headerBits + payload.length * 8 <= dataCapacity * 8) {
        selectedVersion = v
        selectedEcc = ecc
        break
      }
    }
    if (selectedVersion > 0) break
  }
  if (selectedVersion === 0) {
    throw new Error('QR 코드 최대 용량을 초과했습니다.')
  }

  const size = 4 * selectedVersion + 17
  const modules = Array.from({ length: size }, () => Array<boolean>(size).fill(false))
  const isFunction = Array.from({ length: size }, () => Array<boolean>(size).fill(false))

  // Timing patterns
  for (let i = 0; i < size; i++) {
    const dark = i % 2 === 0
    modules[6][i] = dark
    isFunction[6][i] = true
    modules[i][6] = dark
    isFunction[i][6] = true
  }

  // Finder patterns
  drawFinderPattern(modules, isFunction, 3, 3)
  drawFinderPattern(modules, isFunction, 3, size - 4)
  drawFinderPattern(modules, isFunction, size - 4, 3)

  // Alignment patterns
  const alignPos = alignmentPatternPositions(selectedVersion)
  for (let i = 0; i < alignPos.length; i++) {
    for (let j = 0; j < alignPos.length; j++) {
      const skip =
        (i === 0 && j === 0) ||
        (i === 0 && j === alignPos.length - 1) ||
        (i === alignPos.length - 1 && j === 0)
      if (!skip) {
        drawAlignmentPattern(modules, isFunction, alignPos[i], alignPos[j])
      }
    }
  }

  // Reserve format & version areas before placing data
  drawFormatBits(modules, isFunction, selectedEcc, 0)
  drawVersionBits(modules, isFunction, selectedVersion)

  // Encode & interleave data + ECC codewords
  const dataCapacity = numDataCodewords(selectedVersion, selectedEcc)
  const dataCodewords = encodeByteSegments(payload, selectedVersion, dataCapacity)
  const allCodewords = interleaveCodewords(dataCodewords, selectedVersion, selectedEcc)

  // Place data bits in zigzag order
  let bitIndex = 0
  const totalBits = allCodewords.length * 8
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5
    for (let vert = 0; vert < size; vert++) {
      for (let j = 0; j < 2; j++) {
        const col = right - j
        const upward = ((right + 1) & 2) === 0
        const row = upward ? size - 1 - vert : vert
        if (!isFunction[row][col] && bitIndex < totalBits) {
          const byteVal = allCodewords[bitIndex >>> 3]
          modules[row][col] = ((byteVal >>> (7 - (bitIndex & 7))) & 1) !== 0
          bitIndex++
        }
      }
    }
  }

  // Pick optimal mask (0..7)
  let bestMask = 0
  let lowestPenalty = Number.POSITIVE_INFINITY
  for (let mask = 0; mask < 8; mask++) {
    applyMask(modules, isFunction, mask)
    drawFormatBits(modules, isFunction, selectedEcc, mask)
    const penalty = evaluatePenalty(modules)
    if (penalty < lowestPenalty) {
      lowestPenalty = penalty
      bestMask = mask
    }
    applyMask(modules, isFunction, mask)
  }

  applyMask(modules, isFunction, bestMask)
  drawFormatBits(modules, isFunction, selectedEcc, bestMask)

  return {
    version: selectedVersion,
    size,
    ecc: selectedEcc,
    modules
  }
}

export function buildQrSvgModel(
  text: string,
  options: { ecc?: QrErrorCorrectionLevel; margin?: number; label?: string } = {}
): QrSvgModel {
  const margin = Math.max(0, Math.floor(options.margin ?? 4))
  const matrix = encodeQrMatrix(text, options.ecc ?? 'M')
  const totalSize = matrix.size + margin * 2
  const commands: string[] = []

  for (let r = 0; r < matrix.size; r++) {
    let c = 0
    while (c < matrix.size) {
      if (!matrix.modules[r][c]) {
        c++
        continue
      }
      const start = c
      while (c < matrix.size && matrix.modules[r][c]) {
        c++
      }
      const run = c - start
      commands.push(`M${start + margin} ${r + margin}h${run}v1h-${run}z`)
    }
  }

  return {
    version: matrix.version,
    size: matrix.size,
    margin,
    totalSize,
    viewBox: `0 0 ${totalSize} ${totalSize}`,
    pathD: commands.join(''),
    label: options.label ?? 'Google Authenticator TOTP QR 코드'
  }
}

function escapeXmlAttr(value: string): string {
  return value
    .replaceAll('&', '&amp;')
    .replaceAll('"', '&quot;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
}

export function renderQrSvg(
  text: string,
  options: { ecc?: QrErrorCorrectionLevel; margin?: number; label?: string } = {}
): string {
  const model = buildQrSvgModel(text, options)
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="${model.viewBox}" role="img" aria-label="${escapeXmlAttr(model.label)}" shape-rendering="crispEdges"><rect width="${model.totalSize}" height="${model.totalSize}" fill="#ffffff"/><path d="${model.pathD}" fill="#111827"/></svg>`
}
