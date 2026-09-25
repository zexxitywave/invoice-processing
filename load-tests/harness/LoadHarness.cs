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
