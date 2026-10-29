package main

import (
	"crypto/sha256"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math"
	"os"
	"path/filepath"

	"github.com/jx-zheng/Whisper2/internal/carrier/jpegx"
	"github.com/jx-zheng/Whisper2/internal/carrier/pngx"
	"github.com/jx-zheng/Whisper2/internal/stego"
	"github.com/jx-zheng/Whisper2/internal/stego/costs"
	"github.com/jx-zheng/Whisper2/internal/stego/permute"
	"github.com/jx-zheng/Whisper2/internal/stego/stc"
)

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: golden-gen <whisper2-root> <out-dir>")
		os.Exit(2)
	}
	root, out := os.Args[1], os.Args[2]
	if err := os.MkdirAll(out, 0o755); err != nil {
		panic(err)
	}
	write(out, "permute.json", permuteVec())
	write(out, "stc.json", stcVec())
	write(out, "hill.json", hillVec(root))
	write(out, "uerd.json", uerdVec(root))
	write(out, "public-layout.json", layoutVec())
	fmt.Println("wrote", out)
}

func write(dir, name string, v any) {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		panic(err)
	}
	b = append(b, '\n')
	if err := os.WriteFile(filepath.Join(dir, name), b, 0o644); err != nil {
		panic(err)
	}
}

func permuteVec() any {
	var seed [32]byte
	for i := range seed {
		seed[i] = byte(i)
	}
	order, err := permute.Order(1000, seed[:])
	if err != nil {
		panic(err)
	}
	return map[string]any{"n": 1000, "seedHex": hex.EncodeToString(seed[:]), "order": order}
}

func stcVec() any {
	msg := []byte("golden-stc-msg!!")
	n := len(msg) * 8 * 4
	cover := make([]byte, n+5)
	costs := make([]float64, len(cover))
	for i := range cover {
		cover[i] = byte((i*17 + 3) & 1)
		costs[i] = 0.5 + float64(i%7)*0.25
	}
	// A second case with a few wet columns, still reachable.
	coverW := append([]byte(nil), cover...)
	costsW := append([]float64(nil), costs...)
	for i := 0; i < len(costsW); i += 23 {
		costsW[i] = math.Inf(1)
	}
	var cases []any
	for _, h := range []int{7, 10} {
		stego, changes, err := stc.Embed(cover, costs, msg, h)
		if err != nil {
			panic(fmt.Errorf("h=%d: %w", h, err))
		}
		got, err := stc.Extract(stego, len(msg), h)
		if err != nil || string(got) != string(msg) {
			panic(fmt.Errorf("extract h=%d: %v", h, err))
		}
		stegoW, changesW, err := stc.Embed(coverW, costsW, msg, h)
		if err != nil {
			panic(fmt.Errorf("wet h=%d: %w", h, err))
		}
		cases = append(cases, map[string]any{
			"h": h, "messageHex": hex.EncodeToString(msg),
			"cover": bytesAsInts(cover), "costs": nums(costs), "stego": bytesAsInts(stego), "changes": changes,
			"wetCosts": nums(costsW), "wetStego": bytesAsInts(stegoW), "wetChanges": changesW,
		})
	}
	return map[string]any{"cases": cases}
}

func hillVec(root string) any {
	b, err := os.ReadFile(filepath.Join(root, "testdata/png/photo.png"))
	if err != nil {
		panic(err)
	}
	im, err := pngx.Decode(b)
	if err != nil {
		panic(err)
	}
	if im.Width < 64 || im.Height < 64 {
		panic(fmt.Sprintf("photo is %dx%d", im.Width, im.Height))
	}
	spp := im.SPP()
	off := 0
	if spp >= 3 {
		off = 1
	}
	pix := make([]byte, 64*64)
	for y := 0; y < 64; y++ {
		for x := 0; x < 64; x++ {
			pix[y*64+x] = im.Samples[(y*im.Width+x)*spp+off]
		}
	}
	rho, err := costs.HILL(pix, 64, 64)
	if err != nil {
		panic(err)
	}
	return map[string]any{
		"width": 64, "height": 64, "channel": off, "pixels": bytesAsInts(pix), "costs": nums(rho),
	}
}

func uerdVec(root string) any {
	b, err := os.ReadFile(filepath.Join(root, "testdata/jpeg/noise.jpg"))
	if err != nil {
		panic(err)
	}
	f, err := jpegx.Parse(b)
	if err != nil {
		panic(err)
	}
	luma, bx, by, q, err := f.Luma()
	if err != nil {
		panic(err)
	}
	rho, err := costs.UERD(luma, bx, by, q)
	if err != nil {
		panic(err)
	}
	sum := sha256.Sum256(floatBytes(rho))
	return map[string]any{
		"blocksX": bx, "blocksY": by, "nzac": f.NZAC(),
		"quant": q[:], "costsSha256": hex.EncodeToString(sum[:]),
		"costHead": nums(head(rho, 128)), "costTail": nums(tail(rho, 32)),
		"coeffsHead": headInt(luma, 64),
	}
}

func layoutVec() any {
	var rows []any
	for _, rate := range []float64{0.10, 0.05, 0.02} {
		w, salt, rest, err := stego.PublicLayoutIndices(100000, rate)
		if err != nil {
			panic(err)
		}
		rows = append(rows, map[string]any{
			"n": 100000, "rate": rate, "width": w,
			"saltCount": len(salt), "restCount": len(rest),
			"salt": salt,
			"restHead": headInt(rest, 32),
			"saltSha256": sumHex(intBytes(salt)),
			"restSha256": sumHex(intBytes(rest)),
		})
	}
	return map[string]any{"rows": rows}
}

func sumHex(b []byte) string {
	sum := sha256.Sum256(b)
	return hex.EncodeToString(sum[:])
}

func intBytes(v []int) []byte {
	b := make([]byte, len(v)*4)
	for i, x := range v {
		binary.BigEndian.PutUint32(b[i*4:], uint32(x))
	}
	return b
}

func floatBytes(v []float64) []byte {
	b := make([]byte, len(v)*8)
	for i, x := range v {
		binary.BigEndian.PutUint64(b[i*8:], math.Float64bits(x))
	}
	return b
}

func bytesAsInts(b []byte) []int {
	out := make([]int, len(b))
	for i, v := range b {
		out[i] = int(v)
	}
	return out
}

func nums(v []float64) []any {
	out := make([]any, len(v))
	for i, x := range v {
		if math.IsInf(x, 1) {
			out[i] = nil
			continue
		}
		out[i] = x
	}
	return out
}

func head(v []float64, n int) []float64 {
	if len(v) < n {
		n = len(v)
	}
	return append([]float64(nil), v[:n]...)
}

func tail(v []float64, n int) []float64 {
	if len(v) < n {
		return append([]float64(nil), v...)
	}
	return append([]float64(nil), v[len(v)-n:]...)
}

func headInt(v []int, n int) []int {
	if len(v) < n {
		n = len(v)
	}
	return append([]int(nil), v[:n]...)
}
