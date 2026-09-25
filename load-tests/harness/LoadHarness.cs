using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net.Http;
using System.Text;
using System.Threading;

public class ScenarioResult
{
    public string Label;
    public int Threads;
    public int DurationSec;
    public int Total;
    public int HttpErrors;
    public int TransportErrors;
    public Dictionary<int, int> StatusCodes = new Dictionary<int, int>();
    public List<double> Latencies = new List<double>();

    public double Avg, Min, Max, P50, P90, P95, P99, Throughput, ErrorPct;
    public string SortNote;
    public string FirstError;

    public void Summarize()
    {
        if (Latencies.Count == 0) { SortNote = "no samples"; return; }
        Latencies.Sort();
        Avg = Math.Round(Latencies.Average(), 1);
        Min = Math.Round(Latencies[0], 1);
        Max = Math.Round(Latencies[Latencies.Count - 1], 1);
        P50 = Math.Round(Latencies[(int)Math.Floor(Latencies.Count * 0.50)], 1);
        P90 = Math.Round(Latencies[(int)Math.Floor(Latencies.Count * 0.90)], 1);
        P95 = Math.Round(Latencies[(int)Math.Floor(Latencies.Count * 0.95)], 1);
        int p99i = (int)Math.Floor(Latencies.Count * 0.99);
        if (p99i >= Latencies.Count) p99i = Latencies.Count - 1;
        P99 = Math.Round(Latencies[p99i], 1);
        Throughput = Math.Round(Total / (DurationSec == 0 ? 1.0 : (double)DurationSec), 2);
        ErrorPct = Math.Round(((HttpErrors + TransportErrors) * 100.0) / Total, 2);
    }

    public string StatusSummary()
    {
        var parts = new List<string>();
        foreach (var kv in StatusCodes.OrderBy(k => k.Key))
        {
            string k = kv.Key == -1 ? "EXC" : kv.Key.ToString();
            parts.Add(k + "=" + kv.Value);
        }
        return string.Join(" ", parts.ToArray());
    }

    public int Errors { get { return HttpErrors + TransportErrors; } }

    public string MdCell(string s)
    {
        if (string.IsNullOrEmpty(s)) return "-";
        return s.Replace("|", "\\|");
    }

    public string MdNum(double v)
    {
        return v.ToString("0.0");
    }
}

/// Renders a run as a Markdown report so results are readable without a JSON viewer.
public static class Report
{
    private static string Row(ScenarioResult r, int i)
    {
        return "| " + i
             + " | " + r.MdCell(r.Label)
             + " | " + r.Threads
             + " | " + r.Total
             + " | " + r.Throughput.ToString("0.00")
             + " | " + r.MdNum(r.Avg)
             + " | " + r.MdNum(r.Min)
             + " | " + r.MdNum(r.P50)
             + " | " + r.MdNum(r.P90)
             + " | " + r.MdNum(r.P95)
             + " | " + r.MdNum(r.P99)
             + " | " + r.MdNum(r.Max)
             + " | " + r.Errors + " (" + r.ErrorPct.ToString("0.00") + "%)";
    }

    private static string Codes(ScenarioResult r)
    {
        var parts = new List<string>();
        foreach (var kv in r.StatusCodes.OrderBy(k => k.Key))
        {
            string k = kv.Key == -1 ? "transport exception" : kv.Key.ToString();
            parts.Add("`" + k + "` x" + kv.Value);
        }
        if (parts.Count == 0) return "none recorded";
        return string.Join(", ", parts.ToArray());
    }

    public static string Markdown(string title, string target, string generatedAt,
                                  IList<ScenarioResult> results, string notes)
    {
        var sb = new StringBuilder();

        int totalReq = 0, totalErr = 0;
        foreach (var r in results) { totalReq += r.Total; totalErr += r.Errors; }
        double overallErr = totalReq == 0 ? 0 : Math.Round((totalErr * 100.0) / totalReq, 2);

        int maxThreads = 0;
        foreach (var r in results) { if (r.Threads > maxThreads) maxThreads = r.Threads; }

        sb.AppendLine("# " + title);
        sb.AppendLine();
        sb.AppendLine("| | |");
        sb.AppendLine("|---|---|");
        sb.AppendLine("| Target | `" + target + "` |");
        sb.AppendLine("| Generated | " + generatedAt + " |");
        sb.AppendLine("| Scenarios | " + results.Count + " (up to " + maxThreads + " concurrent threads) |");
        sb.AppendLine("| Total requests | " + totalReq + " |");
        sb.AppendLine("| Overall error rate | " + overallErr.ToString("0.00") + "% (" + totalErr + " failed) |");
        if (!string.IsNullOrEmpty(notes))
        {
            sb.AppendLine();
            sb.AppendLine("> " + notes);
        }

        sb.AppendLine();
        sb.AppendLine("## Summary");
        sb.AppendLine();
        sb.AppendLine("All latencies in milliseconds. `req/s` is requests per second sustained across the scenario window.");
        sb.AppendLine();
        sb.AppendLine("| # | Scenario | Threads | Requests | req/s | avg | min | p50 | p90 | p95 | p99 | max | Errors |");
        sb.AppendLine("|--:|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|");
        for (int i = 0; i < results.Count; i++)
        {
            sb.AppendLine(Row(results[i], i + 1));
        }

        sb.AppendLine();
        sb.AppendLine("## Detail");
        for (int i = 0; i < results.Count; i++)
        {
            var r = results[i];
            sb.AppendLine();
            sb.AppendLine("### " + (i + 1) + ". " + r.MdCell(r.Label));
            sb.AppendLine();
            sb.AppendLine("- **Load** " + r.Threads + " thread(s) for " + r.DurationSec + "s, "
                        + r.Total + " requests at " + r.Throughput.ToString("0.00") + " req/s");
            sb.AppendLine("- **Latency** min " + r.MdNum(r.Min)
                        + " / p50 " + r.MdNum(r.P50)
                        + " / p90 " + r.MdNum(r.P90)
                        + " / p95 " + r.MdNum(r.P95)
                        + " / p99 " + r.MdNum(r.P99)
                        + " / max " + r.MdNum(r.Max)
                        + " / avg " + r.MdNum(r.Avg));
            sb.AppendLine("- **Status codes** " + Codes(r));
            sb.AppendLine("- **Errors** " + r.HttpErrors + " http, " + r.TransportErrors
                        + " transport (" + r.ErrorPct.ToString("0.00") + "%)");
            if (!string.IsNullOrEmpty(r.FirstError))
            {
                sb.AppendLine("- **First error** `" + r.FirstError.Replace("`", "'") + "`");
            }
        }

        return sb.ToString();
    }
}

public static class Harness
{
    public static ScenarioResult Run(string label, string method, string url,
                                      string body, int threads, int durationSec,
                                      bool keepAlive, string contentType)
    {
        var r = new ScenarioResult();
        r.Label = label;
        r.Threads = threads;

        var handler = new HttpClientHandler();
        handler.AllowAutoRedirect = true;
        handler.MaxConnectionsPerServer = 1024;

        var client = new HttpClient(handler);
        client.Timeout = TimeSpan.FromSeconds(35);
        if (!keepAlive) client.DefaultRequestHeaders.ConnectionClose = true;

        var sw = Stopwatch.StartNew();
        var workers = new Thread[threads];

        for (int i = 0; i < threads; i++)
        {
            workers[i] = new Thread(delegate()
            {
                while (sw.Elapsed.TotalSeconds < durationSec)
                {
                    HttpResponseMessage resp = null;
                    try
                    {
                        var req = new HttpRequestMessage(new HttpMethod(method), url);
                        if (!string.IsNullOrEmpty(body))
                        {
                            string ct = string.IsNullOrEmpty(contentType) ? "application/json" : contentType;
                            req.Content = new StringContent(body, Encoding.UTF8, ct);
                        }

                        var t0 = Stopwatch.StartNew();
                        resp = client.SendAsync(req).GetAwaiter().GetResult();
                        var ignored = resp.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();
                        t0.Stop();
                        int code = (int)resp.StatusCode;

                        lock (r)
                        {
                            r.Latencies.Add(t0.Elapsed.TotalMilliseconds);
                            r.Total++;
                            if (!r.StatusCodes.ContainsKey(code)) r.StatusCodes[code] = 0;
                            r.StatusCodes[code]++;
                            if (code < 200 || code >= 300) r.HttpErrors++;
                        }
                    }
                    catch (Exception ex)
                    {
                        lock (r)
                        {
                            r.Total++;
                            r.TransportErrors++;
                            if (!r.StatusCodes.ContainsKey(-1)) r.StatusCodes[-1] = 0;
                            r.StatusCodes[-1]++;
                            if (r.FirstError == null)
                                r.FirstError = ex.GetType().Name + ": " + ex.Message;
                        }
                    }
                    finally
                    {
                        if (resp != null) resp.Dispose();
                    }
                }
            });
            workers[i].IsBackground = true;
            workers[i].Start();
        }

        foreach (var t in workers) t.Join();
        sw.Stop();
        client.Dispose();
        handler.Dispose();

        r.DurationSec = (int)Math.Round(sw.Elapsed.TotalSeconds, 0);
        r.Summarize();
        return r;
    }
}
