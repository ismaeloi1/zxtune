/*
 * zxtune addition: access to separate voices output and chip state for visualization.
 */

#ifndef SIDVOICESSINK_H
#define SIDVOICESSINK_H

/**
 * Receiver of separate voices output and chip state snapshots.
 * Called from emulation thread.
 */
class SidVoicesSink
{
public:
    virtual ~SidVoicesSink() = default;

    /// Values per output sample in voices() data
    enum
    {
        STRIDE = 7
    };

    /**
     * @param chip index of chip in order of locking (0 is the main one)
     * @param samples interleaved data, STRIDE values per output sample:
     *        3 voices outputs after filters, 3 oscillator outputs (as OSC3), master volume (0..15)
     * @param count output samples count
     */
    virtual void voices(unsigned int chip, const short* samples, unsigned int count) = 0;

    /**
     * Chip state snapshot, taken each statePeriod() output samples.
     * @param chip index of chip
     * @param regs last values written to registers 0x00..0x18
     * @param osc oscillator output (as OSC3 register) of each voice
     * @param env envelope output (as ENV3 register) of each voice
     */
    virtual void state(unsigned int chip, const unsigned char* regs, const unsigned char* osc, const unsigned char* env) = 0;

    /**
     * @return period of state snapshots in output samples
     */
    virtual unsigned int statePeriod() const = 0;
};

#endif // SIDVOICESSINK_H
