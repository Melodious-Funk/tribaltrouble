"""The policy network of the neural AI and its two file formats.

The network here must compute what tt/src/main/java/com/oddlabs/tt/player/neural/Net.java and NeuralAI.java compute:
fully connected layers with tanh, a policy layer of logits and a value layer, and `prior` added to the logit of every
choice the scripted teacher advises. The advice is the last numbers of the observation, one 1 per head, so adding
`prior` times that part of the observation is the same sum. `export` writes the weights as Net.java reads them, and
`read_trajectory` reads what Trajectory.java writes. Sizes (observation, heads) come from the trajectory files, so
this file knows nothing else about what the numbers mean.
"""

import struct
from pathlib import Path

import numpy as np
import torch
from torch import nn

NET_MAGIC = 0x54544E4E  # "TTNN"
TRAJECTORY_MAGIC = 0x5454544A  # "TTTJ"
NET_VERSION = 2
TRAJECTORY_VERSION = 1


class Policy(nn.Module):
    def __init__(self, observation_size: int, head_sizes: list[int], hidden: tuple[int, ...] = (128, 128),
                 prior: float = 0.0):
        super().__init__()
        self.observation_size = observation_size
        self.head_sizes = list(head_sizes)
        self.prior = float(prior)
        sizes = [observation_size, *hidden]
        self.hidden = nn.ModuleList(nn.Linear(a, b) for a, b in zip(sizes, sizes[1:]))
        self.policy = nn.Linear(sizes[-1], sum(head_sizes))
        self.value = nn.Linear(sizes[-1], 1)
        for layer in self.hidden:
            nn.init.orthogonal_(layer.weight, gain=2 ** 0.5)
            nn.init.zeros_(layer.bias)
        # logits near zero and a zero value at the start: the first net chooses as the prior alone does
        nn.init.orthogonal_(self.policy.weight, gain=0.01)
        nn.init.zeros_(self.policy.bias)
        nn.init.orthogonal_(self.value.weight, gain=1.0)
        nn.init.zeros_(self.value.bias)

    def forward(self, observation: torch.Tensor) -> tuple[torch.Tensor, torch.Tensor]:
        x = observation
        for layer in self.hidden:
            x = torch.tanh(layer(x))
        logits = self.policy(x)
        if self.prior != 0.0:
            logits = logits + self.prior * observation[..., -logits.shape[-1]:]
        return logits, self.value(x).squeeze(-1)

    def evaluate(self, observation, action, allowed):
        """The log probability of `action`, the entropy of the choice, and the value, for each row.

        Disallowed choices get no probability, as in Net.choose.
        """
        logits, value = self(observation)
        log_probability = torch.zeros(observation.shape[0], device=observation.device)
        entropy = torch.zeros_like(log_probability)
        offset = 0
        for head, size in enumerate(self.head_sizes):
            head_logits = logits[:, offset:offset + size].masked_fill(~allowed[:, offset:offset + size], -1e9)
            log_p = torch.log_softmax(head_logits, dim=-1)
            log_probability = log_probability + log_p.gather(1, action[:, head:head + 1]).squeeze(1)
            entropy = entropy - (log_p.exp() * log_p).sum(-1)
            offset += size
        return log_probability, entropy, value

    def export(self, file: Path) -> None:
        """Writes the weights as Net.java reads them (big-endian), through a temporary file."""
        layers = [*self.hidden, self.policy, self.value]
        data = bytearray(struct.pack(">iiifi", NET_MAGIC, NET_VERSION, self.observation_size, self.prior,
                                     len(self.hidden)))
        for layer in layers:
            weight = layer.weight.detach().cpu().numpy()
            data += struct.pack(">ii", weight.shape[1], weight.shape[0])
            data += weight.astype(">f4").tobytes()  # row by row: one output's weights after another
            data += layer.bias.detach().cpu().numpy().astype(">f4").tobytes()
        file.parent.mkdir(parents=True, exist_ok=True)
        temporary = file.with_suffix(".tmp")
        temporary.write_bytes(data)
        temporary.replace(file)


def read_trajectory(file: Path) -> dict | None:
    """One player's decisions in one game, as arrays; None for a file without a whole record."""
    data = file.read_bytes()
    if len(data) < 16:
        return None
    magic, version, observation_size, heads = struct.unpack_from(">iiii", data, 0)
    if magic != TRAJECTORY_MAGIC or version != TRAJECTORY_VERSION:
        raise ValueError(f"{file} is not a trajectory file of version {TRAJECTORY_VERSION}")
    head_sizes = list(struct.unpack_from(f">{heads}i", data, 16))
    outcome_size, slot = struct.unpack_from(">ii", data, 16 + 4 * heads)
    start = 16 + 4 * heads + 8
    logits = sum(head_sizes)
    record = np.dtype([("t", ">f4"), ("observation", ">f4", observation_size), ("action", "i1", heads),
                       ("allowed", "i1", logits), ("log_probability", ">f4"), ("value", ">f4"),
                       ("outcome", ">f4", outcome_size)])
    count = (len(data) - start) // record.itemsize  # a game can end in the middle of a write
    if count == 0:
        return None
    records = np.frombuffer(data, dtype=record, count=count, offset=start)
    return {
        "slot": slot,
        "head_sizes": head_sizes,
        "t": records["t"].astype(np.float32),
        "observation": records["observation"].astype(np.float32),
        "action": records["action"].astype(np.int64),
        "allowed": records["allowed"].astype(bool),
        "log_probability": records["log_probability"].astype(np.float32),
        "value": records["value"].astype(np.float32),
        "outcome": records["outcome"].astype(np.float32),
    }
